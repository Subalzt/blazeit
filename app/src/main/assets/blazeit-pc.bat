<# : BlazeIt laptop helper. Double-click to run. The batch lines below hand this same file to PowerShell.
@echo off
title BlazeIt laptop helper
powershell -NoProfile -ExecutionPolicy Bypass -Command "$f='%~f0'; iex ([IO.File]::ReadAllText($f))"
if errorlevel 1 pause
exit /b
#>

# What this does, all on this laptop, nothing installed:
#  1. Finds the phone running BlazeIt on the network (and again whenever its address changes).
#  2. Serves the BlazeIt page at http://localhost:8787. Chrome treats localhost as secure, so
#     downloads use every connection and the clipboard works without extra clicks.
#  3. Turns the phone's Control tab into this laptop's trackpad and keyboard.
#  4. Joins the phone's direct link (its own offline Wi-Fi) when you start it, or its hotspot in
#     hotspot mode (the laptop keeps internet), and goes back to your Wi-Fi when it stops.
#  5. Switches to a USB cable whenever one is plugged in with USB tethering on: the fastest link.
#  6. Keeps the clipboard in step with the phone (text; the phone's Settings can turn it off).
# Close this window to stop all of it.

$ErrorActionPreference = 'Stop'

# A helper started earlier, often an older version, would keep the page's address
# (localhost:8787) and its own idea of the fastest link, while this one was left on a side
# port nobody opens. So the newest copy takes over: it closes the others and their windows.
$me = Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
$closed = 0
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -match '(blazeit|xoosh)-pc[^\\/'']*\.bat' -and $_.CommandLine -match 'ReadAllText' } |
    ForEach-Object {
        try {
            $window = Get-CimInstance Win32_Process -Filter "ProcessId=$($_.ParentProcessId)"
            # Its screen stream to the phone would outlive it and hold the phone's screen.
            Get-CimInstance Win32_Process -Filter "ParentProcessId=$($_.ProcessId) AND Name='ffmpeg.exe'" |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            Stop-Process -Id $_.ProcessId -Force
            $closed++
            Write-Host ("Closed the BlazeIt helper started at " + $_.CreationDate.ToString('HH:mm') + "; this one takes over.")
            # Its window would otherwise sit at a prompt, or at "Press any key".
            if ($window -and $window.Name -eq 'cmd.exe' -and $window.ProcessId -ne $me.ParentProcessId) { Stop-Process -Id $window.ProcessId -Force }
        } catch { }
    }
if ($closed -gt 0) { Start-Sleep -Milliseconds 500 }

$source = @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

public static class BlazeItPc
{
    const int PhonePort = 8787;
    const int DiscoveryPort = 8788;
    /** Where the page is served on this laptop; the first free of 8787, 8797, 8807. */
    static int localPort = 8787;
    static readonly ManualResetEvent relayReady = new ManualResetEvent(false);

    static volatile string phone;
    /** The phone's address on the USB cable while that is the link in use; null otherwise. */
    static volatile string usbHost;
    /** The streams held open to the phone, dropped when it moves so they reconnect on the new link. */
    static volatile HttpWebRequest controlReq, eventsReq;
    /** Held for as long as this helper runs, so a second copy knows to stop. */
    static Mutex single;
    // The folder keeps the app's earlier name, so a laptop paired before the rename stays paired.
    static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Xoosh");
    static readonly string Ua = "BlazeItPC/1 (" + Environment.MachineName + ")";

    public static void Run()
    {
        // .NET otherwise sends "Expect: 100-continue" with every POST body and waits for a
        // go-ahead the phone never sends, so the request times out.
        ServicePointManager.Expect100Continue = false;
        // .NET allows two connections per server by default, and two are always open to the
        // phone (the trackpad stream and the live events), so everything else would queue.
        ServicePointManager.DefaultConnectionLimit = 32;
        Directory.CreateDirectory(Dir);
        bool first;
        single = new Mutex(true, "Local\\BlazeItLaptopHelper", out first);
        if (!first)
        {
            Say("Another BlazeIt helper is running and could not be closed (it may be running as administrator). Close it, then start this one again.");
            Thread.Sleep(8000);
            return;
        }
        Say("BlazeIt laptop helper. Keep this window open; close it to stop.");
        FindPhone(true);

        Thread relay = new Thread(RelayLoop);
        relay.IsBackground = true;
        relay.Start();

        Thread link = new Thread(LinkLoop);
        link.IsBackground = true;
        link.Start();

        Thread direct = new Thread(DirectLoop);
        direct.IsBackground = true;
        direct.Start();

        // The Windows clipboard may only be touched from a single-threaded apartment.
        Thread clip = new Thread(ClipLoop);
        clip.IsBackground = true;
        clip.SetApartmentState(ApartmentState.STA);
        clip.Start();

        Thread events = new Thread(EventsLoop);
        events.IsBackground = true;
        events.Start();

        ControlLoop();
    }

    static void Say(string s)
    {
        Console.WriteLine(DateTime.Now.ToString("HH:mm:ss") + "  " + s);
    }

    // ------------------------------------------------------------------ finding the phone

    static bool Ping(string host)
    {
        try
        {
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + host + ":" + PhonePort + "/api/ping");
            r.Proxy = null;
            r.Timeout = 1500;
            r.ReadWriteTimeout = 1500;
            r.KeepAlive = false;
            using (WebResponse resp = r.GetResponse())
            using (StreamReader rd = new StreamReader(resp.GetResponseStream()))
            {
                return rd.ReadToEnd().Contains("\"ok\":true");
            }
        }
        catch { return false; }
    }

    /**
     * The phone's address over a USB cable (USB tethering), if one is plugged in: the
     * gateway of the phone's network adapter. Measured at 225-270 MB/s with a USB 3 cable,
     * several times any Wi-Fi link, so it always comes first.
     */
    static List<string> UsbGateways()
    {
        List<string> list = new List<string>();
        foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            string d = ni.Description;
            if (d.IndexOf("NDIS", StringComparison.OrdinalIgnoreCase) < 0 &&
                d.IndexOf("NCM", StringComparison.OrdinalIgnoreCase) < 0 &&
                d.IndexOf("Android", StringComparison.OrdinalIgnoreCase) < 0) continue;
            foreach (GatewayIPAddressInformation g in ni.GetIPProperties().GatewayAddresses)
                if (g.Address.AddressFamily == AddressFamily.InterNetwork) list.Add(g.Address.ToString());
        }
        return list;
    }

    /** Points everything at the phone's address on another link: a cable, a direct link, back to Wi-Fi. */
    static void MoveTo(string host)
    {
        usbHost = UsbGateways().Contains(host) ? host : null;
        if (usbHost != null) CheckCable(host);
        if (phone == host) return;
        phone = host;
        // The trackpad and event streams would otherwise stay on the old link until it times out.
        foreach (HttpWebRequest r in new HttpWebRequest[] { controlReq, eventsReq })
        {
            if (r != null) try { r.Abort(); } catch { }
        }
    }

    static string LinkName(string host)
    {
        if (host == usbHost) return "over the USB cable";
        if (directSsid != null) return "on the phone's " + (directSsid.StartsWith("AndroidShare") ? "direct link" : "hotspot");
        return "over Wi-Fi";
    }

    /**
     * The speed the phone's USB tethering adapter reports, in Mbps; 0 when it is not a cable.
     * Android's tethering reports a figure tied to how the USB port connected: about 426 for
     * USB 2 (which moves about 40 MB/s) and about 852 or more for USB 3 (225-270 MB/s).
     */
    static int UsbLinkMbps(string host)
    {
        foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            foreach (GatewayIPAddressInformation g in ni.GetIPProperties().GatewayAddresses)
                if (g.Address.ToString() == host) return (int)(ni.Speed / 1000000);
        }
        return 0;
    }

    /**
     * This laptop's network adapter that reaches the phone: the one with an address on the
     * phone's subnet (the cable, the phone's hotspot or direct link, or the Wi-Fi the router
     * shares with it). Its byte counters include everything on that link, not only BlazeIt.
     */
    static NetworkInterface LinkAdapter(string host)
    {
        IPAddress target;
        if (host == null || !IPAddress.TryParse(host, out target)) return null;
        byte[] t = target.GetAddressBytes();
        foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            foreach (UnicastIPAddressInformation a in ni.GetIPProperties().UnicastAddresses)
            {
                if (a.Address.AddressFamily != AddressFamily.InterNetwork || a.IPv4Mask == null) continue;
                byte[] ip = a.Address.GetAddressBytes(), mask = a.IPv4Mask.GetAddressBytes();
                bool same = true;
                for (int i = 0; i < 4; i++) if ((ip[i] & mask[i]) != (t[i] & mask[i])) same = false;
                if (same) return ni;
            }
        }
        return null;
    }

    /** Says once per cable when it has come up at USB 2 speed, which no software can fix. */
    static string warnedSlowCable;

    static void CheckCable(string host)
    {
        int mbps = UsbLinkMbps(host);
        if (mbps <= 0 || mbps >= 600 || warnedSlowCable == host) return;
        warnedSlowCable = host;
        Say("The cable is running at USB 2 speed (about 40 MB/s). A USB 3 cable, in a USB-C port on " +
            "this laptop, gives about 250 MB/s. Charging cables are usually USB 2.");
    }

    static List<string> Candidates()
    {
        List<string> list = UsbGateways();
        string saved = Path.Combine(Dir, "phone.txt");
        if (File.Exists(saved) && !list.Contains(File.ReadAllText(saved).Trim())) list.Add(File.ReadAllText(saved).Trim());

        // On the phone's hotspot, the phone *is* the gateway.
        foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            foreach (GatewayIPAddressInformation g in ni.GetIPProperties().GatewayAddresses)
            {
                if (g.Address.AddressFamily == AddressFamily.InterNetwork && !list.Contains(g.Address.ToString()))
                    list.Add(g.Address.ToString());
            }
        }

        // Otherwise ask the network: the phone answers "XOOSH?" on UDP 8788.
        try
        {
            using (UdpClient u = new UdpClient(0))
            {
                u.EnableBroadcast = true;
                byte[] ask = Encoding.ASCII.GetBytes("XOOSH?");
                u.Send(ask, ask.Length, new IPEndPoint(IPAddress.Broadcast, DiscoveryPort));
                foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    foreach (UnicastIPAddressInformation a in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (a.Address.AddressFamily != AddressFamily.InterNetwork || a.IPv4Mask == null) continue;
                        byte[] ip = a.Address.GetAddressBytes(), mask = a.IPv4Mask.GetAddressBytes();
                        for (int i = 0; i < 4; i++) ip[i] = (byte)(ip[i] | ~mask[i]);
                        try { u.Send(ask, ask.Length, new IPEndPoint(new IPAddress(ip), DiscoveryPort)); } catch { }
                    }
                }
                u.Client.ReceiveTimeout = 1200;
                DateTime until = DateTime.Now.AddMilliseconds(1200);
                while (DateTime.Now < until)
                {
                    IPEndPoint from = new IPEndPoint(IPAddress.Any, 0);
                    byte[] got;
                    try { got = u.Receive(ref from); } catch { break; }
                    if (Encoding.UTF8.GetString(got).StartsWith("XOOSH ") && !list.Contains(from.Address.ToString()))
                        list.Add(from.Address.ToString());
                }
            }
        }
        catch { }
        return list;
    }

    static void FindPhone(bool firstTime)
    {
        bool told = false;
        while (true)
        {
            foreach (string c in Candidates())
            {
                if (c.Length > 0 && Ping(c))
                {
                    bool moved = phone != c;
                    MoveTo(c);
                    if (moved) Say("Found the phone at " + c + ", " + LinkName(c) + ".");
                    // The direct link's address is not where to look next time.
                    if (directSsid == null && !UsbGateways().Contains(c)) File.WriteAllText(Path.Combine(Dir, "phone.txt"), c);
                    return;
                }
            }
            // Closed last time while on a direct link that has since ended: go home first.
            if (File.Exists(DirectFile))
            {
                string[] rec = File.ReadAllText(DirectFile).Split('\n');
                string was = rec[0].Trim();
                bool added = rec.Length >= 2 && rec[1].Trim() == "added";
                string home = File.Exists(HomeFile) ? File.ReadAllText(HomeFile).Trim() : "";
                try { File.Delete(DirectFile); } catch { }
                Say("Going back to " + (home.Length > 0 ? home : "your Wi-Fi") + " from an earlier direct link.");
                if (home.Length > 0) RunNetsh("wlan connect name=\"" + home + "\"");
                if (was.Length > 0 && added && was.StartsWith("AndroidShare")) RunNetsh("wlan delete profile name=\"" + was + "\"");
                Thread.Sleep(4000);
                continue;
            }
            if (firstTime)
            {
                Console.Write("Could not find the phone. Is BlazeIt started? Type the address it shows (or press Enter to search again): ");
                string typed = (Console.ReadLine() ?? "").Trim();
                Match m = Regex.Match(typed, @"(\d{1,3}(\.\d{1,3}){3})");
                if (m.Success && Ping(m.Groups[1].Value))
                {
                    MoveTo(m.Groups[1].Value);
                    File.WriteAllText(Path.Combine(Dir, "phone.txt"), phone);
                    Say("Connected to " + phone + ".");
                    return;
                }
            }
            else if (!told)
            {
                Say("Waiting for the phone. Start BlazeIt on it, or check both are on the same Wi-Fi.");
                told = true;
            }
            Thread.Sleep(2000);
        }
    }

    // ------------------------------------------------------------------ pairing

    static HttpWebResponse Http(string method, string path, string cookie, int timeoutMs)
    {
        HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + phone + ":" + PhonePort + path);
        r.Method = method;
        r.Proxy = null;
        r.UserAgent = Ua;
        r.Timeout = timeoutMs;
        r.ReadWriteTimeout = timeoutMs;
        r.KeepAlive = false;
        if (cookie != null) r.Headers["Cookie"] = cookie;
        if (method == "POST") r.ContentLength = 0;
        try { return (HttpWebResponse)r.GetResponse(); }
        catch (WebException e)
        {
            if (e.Response != null) return (HttpWebResponse)e.Response;
            throw;
        }
    }

    static string Body(HttpWebResponse r)
    {
        using (StreamReader rd = new StreamReader(r.GetResponseStream())) return rd.ReadToEnd();
    }

    static string Pair()
    {
        while (true)
        {
            string id, code;
            using (HttpWebResponse r = Http("POST", "/api/pair", null, 5000))
            {
                string b = Body(r);
                if ((int)r.StatusCode == 429) { Say("The phone is busy with other requests; trying again shortly."); Thread.Sleep(5000); continue; }
                id = Regex.Match(b, "\"id\":\"([^\"]+)\"").Groups[1].Value;
                code = Regex.Match(b, "\"code\":\"([^\"]+)\"").Groups[1].Value;
            }
            Say("On the phone, allow \"Laptop control on " + Environment.MachineName + "\". Code: " + code);
            for (int i = 0; i < 125; i++)
            {
                Thread.Sleep(1000);
                using (HttpWebResponse r = Http("GET", "/api/pair/" + id, null, 5000))
                {
                    string b = Body(r);
                    if (b.Contains("APPROVED"))
                    {
                        string set = r.Headers["Set-Cookie"] ?? "";
                        Match m = Regex.Match(set, "xoosh_session=([^;,\\s]+)");
                        if (!m.Success) throw new Exception("the phone approved but sent no session");
                        string cookie = "xoosh_session=" + m.Groups[1].Value;
                        File.WriteAllText(Path.Combine(Dir, "session.txt"), cookie);
                        Say("Allowed. This laptop will not need to ask again.");
                        return cookie;
                    }
                    if (b.Contains("DENIED"))
                    {
                        Say("The phone said no. Press Enter to ask again.");
                        Console.ReadLine();
                        break;
                    }
                    if (b.Contains("EXPIRED")) break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ link report
    //
    // Every two seconds: this laptop's side of the Wi-Fi link (signal, link rates, channel,
    // band, generation) and the round trip to the phone, for the phone's Monitor tab.

    static volatile string session;

    static void LinkLoop()
    {
        while (true)
        {
            // Every 2 s while someone has the monitor open; every 15 s otherwise, which is
            // only there to notice when someone opens it.
            int wait = 15000;
            try
            {
                string cookie = session;
                if (phone != null && cookie != null)
                {
                    Dictionary<string, string> kv = Netsh();
                    Stopwatch sw = Stopwatch.StartNew();
                    int rtt = Ping(phone) ? (int)sw.ElapsedMilliseconds : -1;
                    string json = "{\"ssid\":" + Q(Get(kv, "SSID")) +
                        ",\"signalPercent\":" + Num(Get(kv, "Signal")) +
                        ",\"rxMbps\":" + Num(Get(kv, "Receive rate (Mbps)")) +
                        ",\"txMbps\":" + Num(Get(kv, "Transmit rate (Mbps)")) +
                        ",\"channel\":" + Q(Get(kv, "Channel")) +
                        ",\"band\":" + Q(Get(kv, "Band")) +
                        ",\"radio\":" + Q(Radio(Get(kv, "Radio type"))) +
                        ",\"rttMs\":" + rtt + "}";
                    HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + phone + ":" + PhonePort + "/api/monitor/link");
                    r.Method = "POST";
                    r.Proxy = null;
                    r.UserAgent = Ua;
                    r.Timeout = 3000;
                    r.KeepAlive = false;
                    r.ContentType = "application/json";
                    r.Headers["Cookie"] = cookie;
                    byte[] body = Encoding.UTF8.GetBytes(json);
                    r.ContentLength = body.Length;
                    using (Stream o = r.GetRequestStream()) o.Write(body, 0, body.Length);
                    using (WebResponse resp = r.GetResponse())
                    using (StreamReader rd = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                    {
                        if (rd.ReadToEnd().Contains("\"watch\"")) wait = 2000;
                    }
                }
            }
            catch { }
            Thread.Sleep(wait);
        }
    }

    /** The "Name : value" lines of netsh's description of this Wi-Fi connection. */
    static Dictionary<string, string> Netsh()
    {
        Dictionary<string, string> kv = new Dictionary<string, string>();
        ProcessStartInfo psi = new ProcessStartInfo("netsh", "wlan show interfaces");
        psi.UseShellExecute = false;
        psi.RedirectStandardOutput = true;
        psi.CreateNoWindow = true;
        using (Process pr = Process.Start(psi))
        {
            string text = pr.StandardOutput.ReadToEnd();
            pr.WaitForExit(2000);
            foreach (string raw in text.Split('\n'))
            {
                int colon = raw.IndexOf(" : ");
                if (colon < 0) continue;
                string key = raw.Substring(0, colon).Trim();
                if (!kv.ContainsKey(key)) kv[key] = raw.Substring(colon + 3).Trim();
            }
        }
        return kv;
    }

    static string Get(Dictionary<string, string> kv, string k) { string v; return kv.TryGetValue(k, out v) ? v : ""; }

    static string Num(string v)
    {
        StringBuilder b = new StringBuilder();
        foreach (char c in v) { if (char.IsDigit(c)) b.Append(c); else if (b.Length > 0) break; }
        return b.Length > 0 ? b.ToString() : "0";
    }

    static string Q(string v) { return "\"" + v.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\""; }

    static string Radio(string r)
    {
        if (r.EndsWith("be")) return "Wi-Fi 7";
        if (r.EndsWith("ax")) return "Wi-Fi 6";
        if (r.EndsWith("ac")) return "Wi-Fi 5";
        if (r.EndsWith("n")) return "Wi-Fi 4";
        return r;
    }

    // ------------------------------------------------------------------ direct link and hotspot
    //
    // When the phone offers a fast link for laptops, this laptop joins it: either the
    // phone's direct link (its own offline network, fastest) or, in hotspot mode, the
    // phone's ordinary hotspot, which shares the phone's internet so this laptop stays
    // online. The page keeps working because it talks to localhost, and the relay follows
    // the phone to its address on the new network. When the link goes away, the laptop goes
    // back to the Wi-Fi it was on. A link started only for a phone-to-phone send says so
    // ("laptop":false), and this laptop stays where it is.

    /** The direct link's network name while this laptop is on it; null otherwise. */
    static volatile string directSsid;
    /** The Wi-Fi profile to go back to. */
    static string homeProfile;
    /** True when this helper created the profile it joined, so it may delete it afterwards. */
    static bool addedProfile;
    /** A network that could not be joined, so it is not retried every few seconds. */
    static string gaveUpOn;

    static string DirectFile { get { return Path.Combine(Dir, "direct-wifi.txt"); } }
    static string HomeFile { get { return Path.Combine(Dir, "home-wifi.txt"); } }

    static void DirectLoop()
    {
        if (File.Exists(HomeFile)) homeProfile = File.ReadAllText(HomeFile).Trim();
        if (File.Exists(DirectFile))
        {
            string[] rec = File.ReadAllText(DirectFile).Split('\n');
            directSsid = rec[0].Trim();
            // Only a profile this helper recorded as its own may be deleted; older helpers
            // wrote one line, and a profile of unknown origin is always kept.
            addedProfile = rec.Length >= 2 && rec[1].Trim() == "added";
        }
        int misses = 0, usbMisses = 0;
        while (true)
        {
            Thread.Sleep(2500);
            try
            {
                string cookie = session;
                if (cookie == null || phone == null) continue;

                // A USB cable beats every Wi-Fi link: switch to it, and leave the direct link.
                string usb = null;
                foreach (string g in UsbGateways()) { if (Ping(g)) { usb = g; break; } }
                if (usb != null)
                {
                    usbMisses = 0;
                    if (directSsid != null) LeaveDirect();
                    if (phone != usb)
                    {
                        MoveTo(usb);
                        Say("USB cable to the phone found: using it. It is several times faster than any Wi-Fi link.");
                    }
                    continue;
                }
                // Unplugged, or USB tethering switched off: find the phone over the air again. A
                // cable still listed gets one more look, in case the phone was only slow to answer.
                if (usbHost != null && phone == usbHost &&
                    (!UsbGateways().Contains(usbHost) || ++usbMisses >= 2))
                {
                    usbMisses = 0;
                    Say("The USB cable is gone; looking for the phone over Wi-Fi.");
                    usbHost = null;
                    FindPhone(false);
                    continue;
                }
                string body = null;
                try { using (HttpWebResponse r = Http("GET", "/api/direct", cookie, 3000)) body = Body(r); } catch { }
                if (body == null)
                {
                    // On the link and the phone has gone quiet: the link was stopped.
                    if (directSsid != null && ++misses >= 3) { LeaveDirect(); misses = 0; }
                    continue;
                }
                misses = 0;
                bool on = body.Contains("\"state\":\"on\"") && !body.Contains("\"laptop\":false");
                string ssid = Field(body, "ssid"), host = Field(body, "host");
                if (on && ssid.Length > 0 && host.Length > 0 && directSsid != ssid && gaveUpOn != ssid)
                    JoinDirect(ssid, Field(body, "passphrase"), host, Field(body, "security"), Field(body, "kind") == "hotspot");
                else if (!on && directSsid != null) LeaveDirect();
                if (!on) gaveUpOn = null;
            }
            catch (Exception e) { Say("Direct link: " + e.Message); }
        }
    }

    static string Field(string json, string key)
    {
        Match m = Regex.Match(json, "\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"");
        return m.Success ? Regex.Unescape(m.Groups[1].Value) : "";
    }

    static string Xml(string s) { return System.Security.SecurityElement.Escape(s); }

    static string RunNetsh(string args)
    {
        ProcessStartInfo psi = new ProcessStartInfo("netsh", args);
        psi.UseShellExecute = false;
        psi.RedirectStandardOutput = true;
        psi.CreateNoWindow = true;
        using (Process pr = Process.Start(psi))
        {
            string text = pr.StandardOutput.ReadToEnd();
            pr.WaitForExit(10000);
            return text;
        }
    }

    static void JoinDirect(string ssid, string pass, string host, string security, bool hotspot)
    {
        // A network this laptop already knows (the phone's hotspot, often) is joined with the
        // saved profile, which is left exactly as it was.
        bool known = !RunNetsh("wlan show profile name=\"" + ssid + "\"").Contains("is not found");
        if (!known && pass.Length == 0)
        {
            Say("The phone's hotspot is on, but this laptop does not know its password. Add it in the phone's Settings (Laptop link).");
            gaveUpOn = ssid;
            return;
        }
        string current = Get(Netsh(), "Profile");
        if (current.Length > 0 && current != ssid)
        {
            homeProfile = current;
            File.WriteAllText(HomeFile, current);
        }
        Say(hotspot
            ? "The phone's hotspot is on. Moving this laptop onto it; the internet stays on through the phone."
            : "The phone started its direct link. Moving this laptop onto it; there is no internet while on it.");
        addedProfile = !known;
        if (known) { JoinAndFind(ssid, host, hotspot); return; }
        string xml = "<?xml version=\"1.0\"?><WLANProfile xmlns=\"http://www.microsoft.com/networking/WLAN/profile/v1\">" +
            "<name>" + Xml(ssid) + "</name><SSIDConfig><SSID><name>" + Xml(ssid) + "</name></SSID></SSIDConfig>" +
            "<connectionType>ESS</connectionType><connectionMode>manual</connectionMode><MSM><security>" +
            "<authEncryption><authentication>" + (security == "WPA3" ? "WPA3SAE" : "WPA2PSK") + "</authentication>" +
            "<encryption>AES</encryption><useOneX>false</useOneX></authEncryption>" +
            "<sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>" + Xml(pass) + "</keyMaterial></sharedKey>" +
            "</security></MSM></WLANProfile>";
        string file = Path.Combine(Path.GetTempPath(), "blazeit-direct.xml");
        try
        {
            File.WriteAllText(file, xml);
            RunNetsh("wlan add profile filename=\"" + file + "\" user=current");
        }
        finally { try { File.Delete(file); } catch { } }
        JoinAndFind(ssid, host, hotspot);
    }

    static void JoinAndFind(string ssid, string host, bool hotspot)
    {
        directSsid = ssid;
        File.WriteAllText(DirectFile, ssid + "\n" + (addedProfile ? "added" : "saved"));
        RunNetsh("wlan connect name=\"" + ssid + "\" ssid=\"" + ssid + "\"");
        for (int i = 0; i < 50; i++)
        {
            Thread.Sleep(500);
            if (Ping(host))
            {
                MoveTo(host);
                Say(hotspot ? "On the phone's hotspot. The page carries on over it, and the internet works."
                            : "On the direct link. The page carries on over it at full speed.");
                return;
            }
        }
        Say("Could not reach the phone on " + (hotspot ? "its hotspot" : "its direct link") + "; going back.");
        gaveUpOn = ssid;
        LeaveDirect();
    }

    static void LeaveDirect()
    {
        string ssid = directSsid;
        directSsid = null;
        try { File.Delete(DirectFile); } catch { }
        if (!string.IsNullOrEmpty(homeProfile)) RunNetsh("wlan connect name=\"" + homeProfile + "\"");
        // Only a network this helper added itself (the direct link's, never one you saved) goes.
        if (ssid != null && addedProfile && ssid.StartsWith("AndroidShare")) RunNetsh("wlan delete profile name=\"" + ssid + "\"");
        addedProfile = false;
        Say("The phone's link ended. Back on " + (string.IsNullOrEmpty(homeProfile) ? "your Wi-Fi" : homeProfile) + ".");
        Thread.Sleep(3000);
        FindPhone(false);
    }

    // ------------------------------------------------------------------ clipboard sync
    //
    // Copy on this laptop and it is on the phone, ready to paste; copy on the phone and it
    // lands in this laptop's clipboard (the phone sends it when BlazeIt opens or its tile is
    // tapped). The phone's Settings can turn it off. Text only.

    [DllImport("user32.dll")]
    static extern uint GetClipboardSequenceNumber();

    static readonly Queue<string> clipToSet = new Queue<string>();
    /** The last text seen on, or put on, this laptop's clipboard; never sent back. */
    static volatile string lastClip;
    static volatile bool clipSync = true;

    static void ClipLoop()
    {
        uint seq = GetClipboardSequenceNumber();
        while (true)
        {
            Thread.Sleep(350);
            try
            {
                string pending = null;
                lock (clipToSet) { if (clipToSet.Count > 0) pending = clipToSet.Dequeue(); }
                if (pending != null)
                {
                    for (int i = 0; i < 5; i++)
                    {
                        try { System.Windows.Forms.Clipboard.SetText(pending); break; }
                        catch { Thread.Sleep(120); } // another app has the clipboard open
                    }
                    lastClip = pending;
                    seq = GetClipboardSequenceNumber();
                    continue;
                }
                uint now = GetClipboardSequenceNumber();
                if (now == seq) continue;
                seq = now;
                if (!clipSync || session == null || phone == null) continue;
                string text = null;
                try { if (System.Windows.Forms.Clipboard.ContainsText()) text = System.Windows.Forms.Clipboard.GetText(); } catch { }
                if (string.IsNullOrEmpty(text) || text == lastClip || text.Length > 200000) continue;
                lastClip = text;
                SendClip(text);
            }
            catch { }
        }
    }

    static void SendClip(string text)
    {
        try
        {
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + phone + ":" + PhonePort + "/api/clipboard");
            r.Method = "POST";
            r.Proxy = null;
            r.UserAgent = Ua;
            r.Timeout = 4000;
            r.KeepAlive = false;
            r.ContentType = "application/json";
            r.Headers["Cookie"] = session;
            r.Headers["Bridge-Auto"] = "1";
            byte[] body = Encoding.UTF8.GetBytes("{\"text\":" + Json(text) + "}");
            r.ContentLength = body.Length;
            using (Stream o = r.GetRequestStream()) o.Write(body, 0, body.Length);
            using (WebResponse resp = r.GetResponse()) { }
        }
        catch { }
    }

    static string Json(string v)
    {
        StringBuilder b = new StringBuilder("\"");
        foreach (char c in v)
        {
            if (c == '"') b.Append("\\\"");
            else if (c == '\\') b.Append("\\\\");
            else if (c == '\n') b.Append("\\n");
            else if (c == '\r') b.Append("\\r");
            else if (c == '\t') b.Append("\\t");
            else if (c < ' ') b.Append("\\u").Append(((int)c).ToString("x4"));
            else b.Append(c);
        }
        return b.Append('"').ToString();
    }

    /**
     * The phone's live event stream: the phone's clipboard, and the sync switch. The first
     * clipboard event after connecting is only the phone's stored text, so it never
     * overwrites this laptop's clipboard; only changes after that do.
     */
    static void EventsLoop()
    {
        while (true)
        {
            try
            {
                string cookie = session, at = phone;
                if (cookie == null || at == null) { Thread.Sleep(2000); continue; }
                using (HttpWebResponse st = Http("GET", "/api/state", cookie, 5000))
                    clipSync = !Body(st).Contains("\"clipSync\":false");
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create("http://" + at + ":" + PhonePort + "/events");
                req.Proxy = null;
                req.UserAgent = Ua;
                req.Timeout = 5000;
                req.ReadWriteTimeout = 40000; // the phone sends a heartbeat every 15 s
                req.Headers["Cookie"] = cookie;
                eventsReq = req;
                using (WebResponse resp = req.GetResponse())
                using (StreamReader rd = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                {
                    string ev = null, line;
                    StringBuilder data = new StringBuilder();
                    bool snapshot = true;
                    while ((line = rd.ReadLine()) != null)
                    {
                        if (phone != at) break; // moved to another link (cable, direct link): reconnect there
                        if (line.Length == 0)
                        {
                            string d = data.ToString();
                            if (ev == "clipsync") clipSync = d == "on";
                            else if (ev == "clipboard")
                            {
                                if (snapshot) { snapshot = false; if (lastClip == null) lastClip = d; }
                                else if (clipSync && d.Length > 0 && d != lastClip)
                                {
                                    lastClip = d;
                                    lock (clipToSet) clipToSet.Enqueue(d);
                                }
                            }
                            ev = null;
                            data.Length = 0;
                            continue;
                        }
                        if (line.StartsWith("event:")) ev = line.Substring(6).Trim();
                        else if (line.StartsWith("data:"))
                        {
                            string d = line.Substring(5);
                            if (d.StartsWith(" ")) d = d.Substring(1);
                            if (data.Length > 0) data.Append('\n');
                            data.Append(d);
                        }
                    }
                }
            }
            catch { }
            Thread.Sleep(1500);
        }
    }

    // ------------------------------------------------------------------ trackpad and keyboard

    static void ControlLoop()
    {
        string file = Path.Combine(Dir, "session.txt");
        string cookie = File.Exists(file) ? File.ReadAllText(file).Trim() : null;
        bool announced = false;
        while (true)
        {
            string at = phone;
            try
            {
                if (cookie == null) cookie = Pair();
                session = cookie;
                at = phone;
                HttpWebRequest r = (HttpWebRequest)WebRequest.Create("http://" + at + ":" + PhonePort + "/api/control/stream");
                controlReq = r;
                r.Proxy = null;
                r.UserAgent = Ua;
                r.Timeout = 5000;
                // The phone sends a keep-alive every 3 s while its Control tab is open and every
                // 25 s otherwise (this header says we accept the slow one), so silence this long
                // means it is gone.
                r.ReadWriteTimeout = 40000;
                r.Headers["Bridge-Heartbeat"] = "slow";
                r.Headers["Cookie"] = cookie;
                HttpWebResponse resp;
                try { resp = (HttpWebResponse)r.GetResponse(); }
                catch (WebException e)
                {
                    HttpWebResponse er = e.Response as HttpWebResponse;
                    if (er != null && (int)er.StatusCode == 401)
                    {
                        er.Close();
                        Say("The phone no longer knows this laptop; asking again.");
                        cookie = null;
                        try { File.Delete(file); } catch { }
                        continue;
                    }
                    throw;
                }
                using (resp)
                using (StreamReader rd = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                {
                    if (!announced)
                    {
                        Say("Ready. The phone's Control tab now drives this laptop.");
                        relayReady.WaitOne(3000);
                        if (localPort > 0)
                        {
                            Say("Opening the BlazeIt page at http://localhost:" + localPort + "/ for full-speed transfers.");
                            try { Process.Start("http://localhost:" + localPort + "/"); } catch { }
                        }
                        announced = true;
                    }
                    else Say("Reconnected, " + LinkName(at) + ".");
                    string line;
                    while ((line = rd.ReadLine()) != null)
                    {
                        try { Handle(line); } catch { }
                    }
                }
                if (phone == at) Say("The phone closed the connection.");
            }
            catch (Exception e)
            {
                // Moving to another link drops this stream on purpose; it reconnects there.
                if (phone == at) Say("Lost the phone (" + e.Message + ").");
            }
            controlReq = null;
            Thread.Sleep(800);
            if (phone == null || !Ping(phone)) FindPhone(false);
        }
    }

    static void Handle(string line)
    {
        string[] a = line.Split(' ');
        switch (a[0])
        {
            case "m": Mouse(MOVE, int.Parse(a[1]), int.Parse(a[2]), 0); break;
            case "b": Button(a[1], a[2] == "d"); break;
            case "c": Button(a[1], true); Button(a[1], false); break;
            case "w":
                int wy = int.Parse(a[1]), wx = int.Parse(a[2]);
                if (wy != 0) Mouse(WHEEL, 0, 0, wy);
                if (wx != 0) Mouse(HWHEEL, 0, 0, wx);
                break;
            case "z":
                Key("ctrl", true);
                Mouse(WHEEL, 0, 0, 120 * int.Parse(a[1]));
                Key("ctrl", false);
                break;
            case "kd": Key(a[1], true); break;
            case "ku": Key(a[1], false); break;
            case "k": Key(a[1], true); Key(a[1], false); break;
            case "h":
                string[] keys = a[1].Split('+');
                foreach (string k in keys) Key(k, true);
                for (int i = keys.Length - 1; i >= 0; i--) Key(keys[i], false);
                break;
            case "t": Type(Uri.UnescapeDataString(line.Substring(2))); break;
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx; public int dy; public int mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public InputUnion u; }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint count, INPUT[] inputs, int size);

    const uint MOVE = 0x0001, LEFTDOWN = 0x0002, LEFTUP = 0x0004, RIGHTDOWN = 0x0008, RIGHTUP = 0x0010,
        MIDDLEDOWN = 0x0020, MIDDLEUP = 0x0040, WHEEL = 0x0800, HWHEEL = 0x1000;
    const uint KEY_EXTENDED = 0x0001, KEY_UP = 0x0002, KEY_UNICODE = 0x0004;

    static void Send(INPUT i)
    {
        SendInput(1, new INPUT[] { i }, Marshal.SizeOf(typeof(INPUT)));
    }

    static void Mouse(uint flags, int dx, int dy, int data)
    {
        INPUT i = new INPUT();
        i.type = 0;
        i.u.mi.dx = dx;
        i.u.mi.dy = dy;
        i.u.mi.mouseData = data;
        i.u.mi.dwFlags = flags;
        Send(i);
    }

    static void Button(string which, bool down)
    {
        uint f = which == "r" ? (down ? RIGHTDOWN : RIGHTUP)
            : which == "m" ? (down ? MIDDLEDOWN : MIDDLEUP)
            : (down ? LEFTDOWN : LEFTUP);
        Mouse(f, 0, 0, 0);
    }

    static readonly Dictionary<string, ushort> Vk = new Dictionary<string, ushort>
    {
        { "enter", 0x0D }, { "back", 0x08 }, { "tab", 0x09 }, { "esc", 0x1B }, { "space", 0x20 },
        { "left", 0x25 }, { "up", 0x26 }, { "right", 0x27 }, { "down", 0x28 },
        { "del", 0x2E }, { "insert", 0x2D }, { "home", 0x24 }, { "end", 0x23 }, { "pgup", 0x21 }, { "pgdn", 0x22 },
        { "win", 0x5B }, { "ctrl", 0x11 }, { "alt", 0x12 }, { "shift", 0x10 },
        { "f1", 0x70 }, { "f2", 0x71 }, { "f3", 0x72 }, { "f4", 0x73 }, { "f5", 0x74 }, { "f6", 0x75 },
        { "f7", 0x76 }, { "f8", 0x77 }, { "f9", 0x78 }, { "f10", 0x79 }, { "f11", 0x7A }, { "f12", 0x7B },
    };

    static readonly HashSet<string> Extended = new HashSet<string>
    {
        "left", "up", "right", "down", "del", "insert", "home", "end", "pgup", "pgdn", "win",
    };

    static void Key(string name, bool down)
    {
        ushort vk;
        if (!Vk.TryGetValue(name, out vk))
        {
            // Single letters and digits, for shortcuts such as Ctrl+C.
            if (name.Length != 1 || !char.IsLetterOrDigit(name[0])) return;
            vk = (ushort)char.ToUpperInvariant(name[0]);
        }
        INPUT i = new INPUT();
        i.type = 1;
        i.u.ki.wVk = vk;
        i.u.ki.dwFlags = (down ? 0 : KEY_UP) | (Extended.Contains(name) ? KEY_EXTENDED : 0);
        Send(i);
    }

    static void Type(string text)
    {
        foreach (char ch in text)
        {
            INPUT i = new INPUT();
            i.type = 1;
            i.u.ki.wScan = ch;
            i.u.ki.dwFlags = KEY_UNICODE;
            Send(i);
            i.u.ki.dwFlags = KEY_UNICODE | KEY_UP;
            Send(i);
        }
    }

    // ------------------------------------------------------------------ localhost relay

    [DllImport("kernel32.dll")] static extern bool SetHandleInformation(IntPtr handle, int mask, int flags);

    /**
     * Stops programs this helper starts (scrcpy, ffmpeg, and the adb server scrcpy starts, which
     * lives on) from inheriting this socket. Without it, a replaced helper's page port stays held
     * by them, and the next helper cannot serve the page on it.
     */
    static void KeepToSelf(Socket s)
    {
        try { SetHandleInformation(s.Handle, 1, 0); } catch { } // HANDLE_FLAG_INHERIT off
    }

    static void RelayLoop()
    {
        TcpListener l = null;
        foreach (int port in new int[] { 8787, 8797, 8807 })
        {
            try
            {
                l = new TcpListener(IPAddress.Loopback, port);
                l.Start();
                KeepToSelf(l.Server);
                localPort = port;
                break;
            }
            catch (SocketException) { l = null; }
        }
        if (l == null)
        {
            localPort = 0;
            Say("Could not serve the page on this laptop (ports 8787, 8797 and 8807 are all busy). The trackpad still works.");
        }
        relayReady.Set();
        if (l == null) return;
        Thread sweep = new Thread(SweepLoop);
        sweep.IsBackground = true;
        sweep.Start();
        while (true)
        {
            TcpClient c = l.AcceptTcpClient();
            KeepToSelf(c.Client);
            Thread t = new Thread(delegate ()
            {
                Bridge(c);
            });
            t.IsBackground = true;
            t.Start();
        }
    }

    /** One browser connection relayed to the phone: which address it went to, and when bytes last moved. */
    class Relayed
    {
        public TcpClient browser, toPhone;
        public string host;
        public volatile int last = Environment.TickCount;
    }

    static readonly List<Relayed> relayed = new List<Relayed>();

    /**
     * The browser keeps its connections to localhost open and reuses them, and each one stays
     * tied to the link it was opened over. After a move to the cable, downloads would carry on
     * over the old Wi-Fi. So once such a connection goes quiet (between requests, or because
     * the old link is gone) it is closed, and the browser opens a new one over the new link.
     * A transfer still running over the old link is left to finish.
     */
    static void SweepLoop()
    {
        while (true)
        {
            Thread.Sleep(1000);
            string now = phone;
            Relayed[] all;
            lock (relayed) all = relayed.ToArray();
            foreach (Relayed r in all)
            {
                if (r.host == now || unchecked(Environment.TickCount - r.last) < 1500) continue;
                try { r.browser.Close(); } catch { }
                try { r.toPhone.Close(); } catch { }
            }
        }
    }

    static void Bridge(TcpClient browser)
    {
        Relayed r = new Relayed();
        r.browser = browser;
        r.toPhone = new TcpClient();
        r.host = phone;
        lock (relayed) relayed.Add(r);
        try
        {
            browser.NoDelay = true;
            r.toPhone.NoDelay = true;
            browser.ReceiveBufferSize = browser.SendBufferSize = 4 << 20;
            r.toPhone.ReceiveBufferSize = r.toPhone.SendBufferSize = 4 << 20;
            r.toPhone.Connect(r.host, PhonePort);
            KeepToSelf(r.toPhone.Client);
            NetworkStream a = browser.GetStream(), b = r.toPhone.GetStream();
            Thread up = new Thread(delegate () { Pump(a, b, r.toPhone.Client, r); });
            up.IsBackground = true;
            up.Start();
            Pump(b, a, browser.Client, r);
            up.Join();
        }
        catch { }
        finally
        {
            lock (relayed) relayed.Remove(r);
            browser.Close();
            r.toPhone.Close();
        }
    }

    static void Pump(NetworkStream from, NetworkStream to, Socket toSocket, Relayed r)
    {
        byte[] buf = new byte[1 << 20];
        try
        {
            int n;
            while ((n = from.Read(buf, 0, buf.Length)) > 0)
            {
                r.last = Environment.TickCount;
                to.Write(buf, 0, n);
            }
        }
        catch { }
        try { toSocket.Shutdown(SocketShutdown.Send); } catch { }
    }
}
'@

Add-Type -TypeDefinition $source -Language CSharp -ReferencedAssemblies System.Windows.Forms
[BlazeItPc]::Run()
