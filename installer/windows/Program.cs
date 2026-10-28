// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Drawing;
using System.Reflection;
using System.Windows.Forms;
using System.Threading.Tasks;
using System.Net;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Security.Cryptography;
using System.Collections.Generic;

namespace MarcoInstaller {
public static class Program {
    [STAThread] public static int Main(string[] args) {
        if(args.Length>0&&args[0]=="--self-test")return SelfTest();
        if(args.Length==2&&args[0]=="--probe"){
            try{using(var adb=new AdbWire(args[1],5555,Console.WriteLine)){Console.WriteLine(adb.Shell("getprop ro.build.version.sdk"));Console.WriteLine("CONNECTED");}return 0;}
            catch(Exception e){Console.WriteLine(e.Message);return 1;}
        }
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);Application.Run(new InstallerForm());return 0;
    }
    public static Stream Payload(){return Assembly.GetExecutingAssembly().GetManifestResourceStream("MarcoTV.apk");}
    public static void Verify(){using(var p=Payload())using(var sha=SHA256.Create()){if(p==null||p.Length!=ReleaseInfo.Size||BitConverter.ToString(sha.ComputeHash(p)).Replace("-","").ToLowerInvariant()!=ReleaseInfo.Sha)throw new IOException("助手中的安装包校验失败，请从官方镜像重新下载助手。");}}
    static int SelfTest(){try{Verify();using(var k=new RSACryptoServiceProvider(2048)){byte[] p=AdbWire.PublicKey(k.ExportParameters(false));if(p.Length!=524||BitConverter.ToInt32(p,0)!=64||BitConverter.ToInt32(p,520)!=65537)throw new Exception("public key");}foreach(string s in new[]{"127.0.0.1","8.8.8.8","example.com","::1","1;exit"}){bool blocked=false;try{AdbWire.Address(s);}catch{blocked=true;}if(!blocked)throw new Exception("address policy");}Console.WriteLine("PASS payload, RSA key format, private-network address policy");return 0;}catch(Exception e){Console.WriteLine(e.Message);return 1;}}
}
public sealed class InstallerForm : Form {
    TextBox address=new TextBox(),port=new TextBox(),status=new TextBox();ComboBox brands=new ComboBox(),devices=new ComboBox();
    Button connect,install,launch,cancel,find;ProgressBar progress=new ProgressBar();volatile AdbWire adb;volatile int generation;bool busy;string identity="";
    readonly Color ink=Color.FromArgb(233,239,249),muted=Color.FromArgb(165,181,203),panel=Color.FromArgb(28,41,60);
    public InstallerForm(){
        Text="Marco TV 安装助手 · Pilipala";ClientSize=new Size(780,730);MinimumSize=new Size(740,700);BackColor=Color.FromArgb(13,21,34);ForeColor=ink;Font=new Font("Microsoft YaHei UI",10);AutoScaleMode=AutoScaleMode.Dpi;
        var layout=new TableLayoutPanel(){Dock=DockStyle.Fill,Padding=new Padding(28),ColumnCount=1,RowCount=10};Controls.Add(layout);
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute,55));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,42));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,42));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,86));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,48));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,46));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,52));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,25));layout.RowStyles.Add(new RowStyle(SizeType.Percent,100));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,45));
        layout.Controls.Add(new Label(){Text="把 Marco TV 装到电视",Font=new Font(Font.FontFamily,22,FontStyle.Bold),Dock=DockStyle.Fill},0,0);
        layout.Controls.Add(new Label(){Text="无需 U 盘 · 无需命令 · 内含正式版 "+ReleaseInfo.Version+"\nWindows 10 / 11 · 安装助手 1.0.0",ForeColor=muted,Dock=DockStyle.Fill},0,1);
        brands.DropDownStyle=ComboBoxStyle.DropDownList;brands.Width=420;brands.Items.AddRange(new object[]{"选择电视系统，查看准备步骤","小米 / Redmi 国内旧版（部分机型）","Android TV / Google TV","TCL / 雷鸟 / 海信 / 创维等国内系统","不是 Android 或不确定"});brands.SelectedIndex=0;layout.Controls.Add(brands,0,2);
        var guide=new Label(){Text="1  电视和电脑连接同一个家庭网络。\n2  在电视设置中开启“网络调试 / ADB 调试”，查看电视 IP。\n仅提供“无线调试配对码”的电视，暂不支持此版助手。",ForeColor=muted,Dock=DockStyle.Fill};layout.Controls.Add(guide,0,3);
        brands.SelectedIndexChanged+=(s,e)=>{string[] g={"选择品牌后查看对应提示。","设置 → 账户与安全 → 允许 ADB 调试（旧版路径）。\n在网络详情查看 IP；新固件请以本机设置为准。","设置 → 系统 / 设备偏好设置 → 关于 → 连续选择版本号。\n开发者选项中须有可用的网络调试；仅 USB 调试并不保证可连接。","先到系统信息核对型号和 Android 版本。\n网络调试入口因固件不同，请查本机帮助；不使用工程菜单代码。","本安装包要求 Android 6.0 以上。Tizen、webOS、Roku、tvOS 不适用。\n华为、荣耀、VIDAA 请先确认 APK 兼容性。"};guide.Text=g[brands.SelectedIndex]+"\n完整路线可点击下方“安装说明”。";};
        var row=new FlowLayoutPanel(){Dock=DockStyle.Fill};row.Controls.Add(new Label(){Text="电视 IP",Width=65,Padding=new Padding(0,7,0,0)});address.Width=205;row.Controls.Add(address);row.Controls.Add(new Label(){Text="端口",Width=45,Padding=new Padding(0,7,0,0)});port.Text="5555";port.Width=75;row.Controls.Add(port);find=Button("查找电视",async()=>await Discover());row.Controls.Add(find);layout.Controls.Add(row,0,4);
        devices.DropDownStyle=ComboBoxStyle.DropDownList;devices.Dock=DockStyle.Fill;devices.Items.Add("也可直接填写电视网络详情中的 IP");devices.SelectedIndex=0;devices.SelectedIndexChanged+=(s,e)=>{if(devices.SelectedIndex>=0&&devices.SelectedItem.ToString().Contains(":")){string[] a=devices.SelectedItem.ToString().Split(':');address.Text=a[0];port.Text=a[1];}};layout.Controls.Add(devices,0,5);
        var actions=new FlowLayoutPanel(){Dock=DockStyle.Fill};connect=Button("① 连接电视",async()=>await Connect());install=Button("② 安装 "+ReleaseInfo.Version,async()=>await Install());install.Enabled=false;launch=Button("打开桌面",async()=>await Run(()=>{RequireConnected();adb.Shell("am start -n com.pilipala.tvhome/.MainActivity");Say("已请求打开 Marco TV，请看电视屏幕。");}));launch.Enabled=false;cancel=Button("断开",()=>{Disconnect();Say("已断开；如果电视已经开始安装，请在电视核对结果后重试。");return Task.FromResult(0);});actions.Controls.AddRange(new Control[]{connect,install,launch,cancel});layout.Controls.Add(actions,0,6);
        progress.Dock=DockStyle.Fill;layout.Controls.Add(progress,0,7);
        status.Multiline=true;status.ReadOnly=true;status.BorderStyle=BorderStyle.None;status.BackColor=panel;status.ForeColor=ink;status.ScrollBars=ScrollBars.Vertical;status.Dock=DockStyle.Fill;status.Text="准备好后点击“连接电视”。电视可能弹出授权窗口，请用遥控器选择允许。\r\n\r\n安装会保留原有配置，不会卸载原厂桌面，也不会自动修改默认桌面或系统权限。";layout.Controls.Add(status,0,8);
        var foot=new FlowLayoutPanel(){Dock=DockStyle.Fill};var link=new LinkLabel(){Text="安装说明",AutoSize=true,LinkColor=Color.LightSkyBlue,Margin=new Padding(0,12,18,0)};link.LinkClicked+=(s,e)=>System.Diagnostics.Process.Start("https://marcotv.wlj9919.top/install/");foot.Controls.Add(link);foot.Controls.Add(new Label(){Text="Pilipala · 免费开源，若付费购买请申请退款",AutoSize=true,ForeColor=muted,Margin=new Padding(0,12,0,0)});layout.Controls.Add(foot,0,9);
        address.TextChanged+=(s,e)=>{if(!busy)Disconnect();};port.TextChanged+=(s,e)=>{if(!busy)Disconnect();};FormClosed+=(s,e)=>Disconnect();
    }
    Button Button(string text,Func<Task> action){var b=new Button(){Text=text,AutoSize=true,Height=36,FlatStyle=FlatStyle.Flat,BackColor=panel,ForeColor=ink,Margin=new Padding(0,0,10,0)};b.Click+=async(s,e)=>await action();return b;}
    void UI(Action a){if(!IsDisposed&&IsHandleCreated){try{BeginInvoke(a);}catch(InvalidOperationException){}}}
    void Say(string text){UI(()=>status.AppendText("\r\n"+text+"\r\n"));}
    void Disconnect(){generation++;var old=adb;adb=null;if(old!=null)old.Dispose();install.Enabled=false;launch.Enabled=false;identity="";}
    void RequireConnected(){if(adb==null)throw new IOException("请先连接电视。");}
    async Task Run(Action task){if(busy)return;busy=true;connect.Enabled=find.Enabled=install.Enabled=launch.Enabled=address.Enabled=port.Enabled=devices.Enabled=false;try{await Task.Run(task);}catch(Exception e){Say("未完成："+Friendly(e));Disconnect();}finally{busy=false;if(!IsDisposed){connect.Enabled=find.Enabled=address.Enabled=port.Enabled=devices.Enabled=true;install.Enabled=adb!=null;launch.Enabled=adb!=null;}}}
    static string Friendly(Exception e){if(e is SocketException)return "电视连接失败。检查同一网络、IP、端口和电视调试开关；仅支持普通网络 ADB。";return e.Message;}
    async Task Connect(){string host=address.Text.Trim();int p;if(!int.TryParse(port.Text,out p)){Say("端口请输入数字。");return;}Disconnect();int attempt=generation;await Run(()=>{Say("正在连接…");var a=new AdbWire();adb=a;try{if(attempt!=generation)throw new IOException("连接已取消。");a.Connect(host,p,Say);int sdk;if(!int.TryParse(a.Shell("getprop ro.build.version.sdk"),out sdk)||sdk<23)throw new IOException("电视系统不符合 Android 6.0 以上要求。");string name=a.Shell("getprop ro.product.manufacturer")+" "+a.Shell("getprop ro.product.model");if(attempt!=generation||adb!=a)throw new IOException("连接已取消。");identity=name;Say("已连接："+identity+"（Android API "+sdk+"）\r\n请核对这是目标电视，再点击安装。连接成功不代表所有桌面管理功能适配。");}catch{a.Dispose();throw;}});}
    async Task Install(){if(adb==null)return;if(MessageBox.Show(this,"将 Marco TV "+ReleaseInfo.Version+" 安装到：\n"+identity+"\n\n保留已有配置。安装成功后可打开桌面。","确认目标电视",MessageBoxButtons.OKCancel,MessageBoxIcon.Information)!=DialogResult.OK)return;
        progress.Value=0;await Run(()=>{RequireConnected();Program.Verify();Say("安装包校验通过，正在传送…");const string path="/data/local/tmp/marcotv-installer.apk";try{using(var p=Program.Payload())adb.Push(p,ReleaseInfo.Size,path,n=>UI(()=>progress.Value=n));Say("电视正在安装，请稍候…");string r=adb.Shell("pm install -r "+path);if(!r.Split('\n').Any(x=>x.Trim()=="Success"))throw new IOException(InstallError(r));Say("安装成功。点击“打开桌面”，在电视按首次引导选择需要的功能。\r\n若此前开启系统最近任务辅助服务，更新后仍需重新启动该服务。");}finally{try{if(adb!=null)adb.Shell("rm -f "+path);}catch{}}});
    }
    public static string InstallError(string r){if(r.Contains("VERSION_DOWNGRADE"))return "电视已有更新版本，已停止安装，不会降级。";if(r.Contains("UPDATE_INCOMPATIBLE")||r.Contains("SIGNATURE"))return "签名不一致。原应用和数据已保留，请先导出备份并核对来源，不要直接卸载。";if(r.Contains("INSUFFICIENT_STORAGE"))return "电视空间不足，请释放空间后重试。";if(r.Contains("USER_RESTRICTED")||r.Contains("SECURITY"))return "电视限制了应用安装，请按本机设置开放安装权限。";return "电视未确认安装成功。请检查电视上的安装限制或提示后重试（原应用不会被主动卸载）。";}
    async Task Discover(){await Run(()=>{Say("正在查找当前家庭网络中开放 5555 端口的设备…");var hosts=new HashSet<string>();foreach(var ni in NetworkInterface.GetAllNetworkInterfaces()){if(ni.OperationalStatus!=OperationalStatus.Up||(ni.NetworkInterfaceType!=NetworkInterfaceType.Ethernet&&ni.NetworkInterfaceType!=NetworkInterfaceType.Wireless80211))continue;foreach(var u in ni.GetIPProperties().UnicastAddresses){if(u.Address.AddressFamily!=AddressFamily.InterNetwork)continue;byte[] b=u.Address.GetAddressBytes(),mask=u.IPv4Mask.GetAddressBytes();if(!(mask[0]==255&&mask[1]==255&&mask[2]==255&&mask[3]==0))continue;try{AdbWire.Address(u.Address.ToString());}catch{continue;}for(int i=1;i<255;i++){if(i==b[3])continue;hosts.Add(b[0]+"."+b[1]+"."+b[2]+"."+i);}if(hosts.Count>=508)break;}if(hosts.Count>=508)break;}
        var found=new System.Collections.Concurrent.ConcurrentBag<string>();Parallel.ForEach(hosts,new ParallelOptions(){MaxDegreeOfParallelism=16},host=>{using(var c=new TcpClient()){try{var a=c.BeginConnect(host,5555,null,null);try{if(a.AsyncWaitHandle.WaitOne(250)){c.EndConnect(a);found.Add(host+":5555");}}finally{a.AsyncWaitHandle.Close();}}catch{}}});UI(()=>{devices.Items.Clear();foreach(var h in found.OrderBy(x=>x))devices.Items.Add(h);if(devices.Items.Count>0)devices.SelectedIndex=0;});Say(found.Count>0?"发现 "+found.Count+" 个候选设备，请选择后连接并核对型号。":"未找到。请手动填写电视 IP 和实际端口；访客网络隔离或没有网络调试也会导致找不到。");});}
}
}
