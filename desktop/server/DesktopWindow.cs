using System;
using System.Drawing;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal sealed class QingjianWindow : Form
{
    private const string Host = "studio.qingjian.local";
    private const string Entry = "https://" + Host + "/#shelf";
    private readonly string profile;
    private readonly WebView2 view = new WebView2 { Dock = DockStyle.Fill };
    private readonly HttpClient client = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
    private readonly string token = Guid.NewGuid().ToString("N") + Guid.NewGuid().ToString("N");
    private readonly CancellationTokenSource shutdown = new CancellationTokenSource();
    private Process service;
    private Uri address;
    private bool permitClose;

    [STAThread]
    private static void Main()
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        var roaming = Environment.GetEnvironmentVariable("APPDATA") ?? Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        var data = Path.Combine(roaming, "Qingjian");
        string key;
        using (var hash = SHA256.Create()) key = BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(data.ToUpperInvariant())));
        bool first;
        using (var instance = new Mutex(true, "Local\\Qingjian-" + key, out first))
            if (first) Application.Run(new QingjianWindow(data));
    }

    private QingjianWindow(string data)
    {
        profile = data;
        Text = "青简 · 小说写作工作台";
        StartPosition = FormStartPosition.CenterScreen;
        Size = new Size(1400, 900);
        MinimumSize = new Size(640, 570);
        FormBorderStyle = FormBorderStyle.None;
        Padding = new Padding(5);
        BackColor = Color.FromArgb(244, 247, 252);
        view.DefaultBackgroundColor = BackColor;
        Controls.Add(view);
        client.Timeout = TimeSpan.FromMinutes(4);
        Shown += StartWindow;
        Activated += delegate { SetMemoryTarget(CoreWebView2MemoryUsageTargetLevel.Normal); };
        Deactivate += delegate { SetMemoryTarget(CoreWebView2MemoryUsageTargetLevel.Low); };
        Resize += delegate { Padding = WindowState == FormWindowState.Maximized ? Padding.Empty : new Padding(5); };
    }

    protected override CreateParams CreateParams
    {
        get {
            var settings = base.CreateParams;
            settings.Style |= 0x000F0000;
            return settings;
        }
    }

    protected override void WndProc(ref Message message)
    {
        const int nonClientHitTest = 0x0084;
        if (message.Msg == nonClientHitTest && WindowState == FormWindowState.Normal)
        {
            long bits = message.LParam.ToInt64();
            Point p = PointToClient(new Point(unchecked((short)(bits & 0xffff)), unchecked((short)((bits >> 16) & 0xffff))));
            int x = p.X < 5 ? 1 : p.X >= ClientSize.Width - 5 ? 2 : 0;
            int y = p.Y < 5 ? 1 : p.Y >= ClientSize.Height - 5 ? 2 : 0;
            if (x != 0 || y != 0)
            {
                message.Result = (IntPtr)(y == 0 ? 9 + x : x == 0 ? (y == 1 ? 12 : 15) : (y == 1 ? 12 + x : 15 + x));
                return;
            }
        }
        base.WndProc(ref message);
    }

    private async void StartWindow(object sender, EventArgs args)
    {
        try
        {
            await StartService();
            var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(profile, "WebView2"));
            await view.EnsureCoreWebView2Async(env);
            var core = view.CoreWebView2;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.IsGeneralAutofillEnabled = false;
            core.Settings.IsPasswordAutosaveEnabled = false;
            core.Settings.IsNonClientRegionSupportEnabled = true;
            core.AddWebResourceRequestedFilter("https://" + Host + "/*", CoreWebView2WebResourceContext.All, CoreWebView2WebResourceRequestSourceKinds.All);
            core.WebResourceRequested += ProxyRequest;
            core.WebMessageReceived += HandleWindowAction;
            core.NavigationStarting += delegate(object _, CoreWebView2NavigationStartingEventArgs step) { step.Cancel = !IsStudio(step.Uri); };
            core.NewWindowRequested += OpenExternal;
            core.PermissionRequested += delegate(object _, CoreWebView2PermissionRequestedEventArgs request) { request.State = CoreWebView2PermissionState.Deny; };
            core.Navigate(Entry);
        }
        catch (Exception error)
        {
            if (IsDisposed) return;
            string hint = error is WebView2RuntimeNotFoundException ? "请安装 Microsoft Edge WebView2 Runtime。" : error.Message;
            MessageBox.Show(this, hint, "青简启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            permitClose = true;
            Close();
        }
    }

    private async Task StartService()
    {
        var root = AppDomain.CurrentDomain.BaseDirectory;
        var options = new ProcessStartInfo(Path.Combine(root, "runtime", "node.exe"), "\"app\\server\\desktop-service.js\"") {
            WorkingDirectory = root, CreateNoWindow = true, UseShellExecute = false,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        options.EnvironmentVariables["QINGJIAN_DESKTOP_TOKEN"] = token;
        options.EnvironmentVariables["QINGJIAN_DATA_DIR"] = Path.Combine(profile, "books");
        options.EnvironmentVariables.Remove("NODE_OPTIONS");
        service = Process.Start(options);
        service.BeginErrorReadLine();
        var line = service.StandardOutput.ReadLineAsync();
        if (await Task.WhenAny(line, Task.Delay(10000, shutdown.Token)) != line) throw new IOException("本地书稿服务启动超时。");
        int port;
        if (!int.TryParse(await line, out port) || port < 1 || port > 65535) throw new IOException("本地书稿服务未能启动。");
        address = new Uri("http://127.0.0.1:" + port);
    }

    private static bool IsStudio(string uri)
    {
        Uri url;
        return Uri.TryCreate(uri, UriKind.Absolute, out url) && url.Scheme == "https" && url.Host == Host && url.IsDefaultPort && url.UserInfo.Length == 0;
    }

    private async void ProxyRequest(object sender, CoreWebView2WebResourceRequestedEventArgs action)
    {
        var env = view.CoreWebView2.Environment;
        var deferral = action.GetDeferral();
        try
        {
            var request = action.Request;
            if (!IsStudio(request.Uri))
            {
                action.Response = env.CreateWebResourceResponse(null, 403, "Forbidden", "");
                return;
            }
            var source = new Uri(request.Uri);
            var target = new UriBuilder(address) { Path = source.AbsolutePath, Query = source.Query.TrimStart('?') }.Uri;
            using (var outgoing = new HttpRequestMessage(new HttpMethod(request.Method), target))
            {
                outgoing.Headers.Add("x-qingjian-desktop", token);
                if (request.Content != null)
                {
                    var body = new MemoryStream();
                    request.Content.CopyTo(body);
                    body.Position = 0;
                    outgoing.Content = new StreamContent(body);
                    if (request.Headers.Contains("Content-Type")) outgoing.Content.Headers.TryAddWithoutValidation("Content-Type", request.Headers.GetHeader("Content-Type"));
                }
                using (var incoming = await client.SendAsync(outgoing, shutdown.Token))
                {
                    var body = new MemoryStream(await incoming.Content.ReadAsByteArrayAsync(), false);
                    var headers = incoming.Headers.Concat(incoming.Content.Headers)
                        .Where(item => !new[] { "Connection", "Keep-Alive", "Transfer-Encoding" }.Contains(item.Key, StringComparer.OrdinalIgnoreCase))
                        .Select(item => item.Key + ": " + string.Join(", ", item.Value));
                    action.Response = env.CreateWebResourceResponse(body, (int)incoming.StatusCode, incoming.ReasonPhrase, string.Join("\r\n", headers));
                }
            }
        }
        catch (Exception)
        {
            if (shutdown.IsCancellationRequested) return;
            var body = new MemoryStream(Encoding.UTF8.GetBytes("{\"error\":\"本地书稿服务暂时不可用。\"}"), false);
            action.Response = env.CreateWebResourceResponse(body, 503, "Service Unavailable", "Content-Type: application/json; charset=utf-8");
        }
        finally { deferral.Complete(); }
    }

    private void HandleWindowAction(object sender, CoreWebView2WebMessageReceivedEventArgs args)
    {
        if (!IsStudio(args.Source)) return;
        switch (args.TryGetWebMessageAsString())
        {
            case "minimize": WindowState = FormWindowState.Minimized; break;
            case "maximize": WindowState = WindowState == FormWindowState.Maximized ? FormWindowState.Normal : FormWindowState.Maximized; break;
            case "close": permitClose = true; Close(); break;
            case "dark": ChangeTheme(Color.FromArgb(21, 26, 36)); break;
            case "light": ChangeTheme(Color.FromArgb(244, 247, 252)); break;
            case "active": SetMemoryTarget(CoreWebView2MemoryUsageTargetLevel.Normal); break;
            case "idle": SetMemoryTarget(CoreWebView2MemoryUsageTargetLevel.Low); break;
        }
    }

    private void ChangeTheme(Color surface)
    {
        BackColor = surface;
        view.DefaultBackgroundColor = surface;
    }

    private void OpenExternal(object sender, CoreWebView2NewWindowRequestedEventArgs args)
    {
        args.Handled = true;
        Uri url;
        var hosts = new[] { "auth.openai.com", "platform.openai.com", "platform.deepseek.com", "help.openai.com" };
        if (Uri.TryCreate(args.Uri, UriKind.Absolute, out url) && url.Scheme == "https" && url.UserInfo.Length == 0 && hosts.Contains(url.Host))
            Process.Start(new ProcessStartInfo(url.AbsoluteUri) { UseShellExecute = true });
    }

    private void SetMemoryTarget(CoreWebView2MemoryUsageTargetLevel level)
    {
        try { if (view.CoreWebView2 != null) view.CoreWebView2.MemoryUsageTargetLevel = level; }
        catch (NotImplementedException) { }
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!permitClose && e.CloseReason == CloseReason.UserClosing && view.CoreWebView2 != null)
        {
            e.Cancel = true;
            AskToClose();
        }
        base.OnFormClosing(e);
    }

    private async void AskToClose()
    {
        var handled = await view.CoreWebView2.ExecuteScriptAsync("(() => { const button = document.querySelector('[data-close-window]'); if (!button) return false; button.click(); return true; })()");
        if (handled == "true") return;
        permitClose = true;
        Close();
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        shutdown.Cancel();
        client.Dispose();
        if (service != null)
        {
            try { if (!service.HasExited) service.StandardInput.Close(); }
            catch (InvalidOperationException) { }
            service.Dispose();
        }
        base.OnFormClosed(e);
    }
}
