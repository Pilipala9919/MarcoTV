# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Start or stop Marco TV's local task bridge using an already authorized ADB connection."""
import argparse,re,shlex,subprocess,time

PACKAGE='com.pilipala.tvhome'
BRIDGE_NAME='marcotv-task-bridge'
BRIDGE_CLASS='com.pilipala.tvhome.TaskBridgeServer'
APK_PATH_RE=re.compile(r'(?:/data/app/|/mnt/expand/[A-Za-z0-9_-]+/app/)[A-Za-z0-9_./=+~-]+\.apk')


class ProcessListUnavailableError(RuntimeError):
    """Raised when neither `ps -A` nor `ps` returns a verifiable PID table."""


def _ps_pid_column(line):
    cols=line.split()
    if 'PID' not in cols:return -1
    if not any(c in cols for c in ('USER','PPID','NAME','CMD','COMMAND')):return -1
    return cols.index('PID')


def _is_positive_pid(token):
    return bool(token) and token.isdigit() and int(token)>0


def has_ps_header(output):
    checked=0
    for raw in (output or '').splitlines():
        line=raw.strip()
        if not line:continue
        if _ps_pid_column(line)>=0:return True
        checked+=1
        if checked>=2:break
    return False


def has_valid_ps_listing(output):
    pid_col=-1;header_checked=0
    for raw in (output or '').splitlines():
        line=raw.strip()
        if not line:continue
        if pid_col<0:
            col=_ps_pid_column(line)
            if col>=0:
                pid_col=col
                continue
            header_checked+=1
            if header_checked>=2:return False
            continue
        cols=line.split()
        if len(cols)>pid_col and _is_positive_pid(cols[pid_col]):
            return True
    return False


def parse_bridge_pids(output):
    if not has_valid_ps_listing(output):return []
    pids=[];seen=set();pid_col=-1
    for raw in (output or '').splitlines():
        line=raw.strip()
        if not line:continue
        if pid_col<0:
            header_col=_ps_pid_column(line)
            if header_col>=0:pid_col=header_col
            continue
        cols=line.split()
        if len(cols)<=pid_col or not _is_positive_pid(cols[pid_col]):continue
        name=cols[-1]
        if name in (BRIDGE_NAME,'marcotv-task-br') or BRIDGE_CLASS in cols:
            if cols[pid_col] not in seen:
                seen.add(cols[pid_col]);pids.append(cols[pid_col])
    return pids


def is_bridge_cmdline(command):
    cleaned=(command or '').replace('\x00',' ').strip()
    if not cleaned:return False
    if cleaned==BRIDGE_NAME:return True
    return BRIDGE_CLASS in cleaned.split()


def parse_current_user(am_output,pm_users_output=''):
    raw=(am_output or '').strip()
    if raw.isdigit():return raw
    running=re.findall(r'UserInfo\{(\d+):[^}]*\}\s+running',pm_users_output or '')
    if len(running)==1:return running[0]
    return '0'


def parse_uid_from_packages(output):
    match=re.search(r'^package:com\.pilipala\.tvhome uid:(\d+)$',output or '',re.M)
    return match.group(1) if match else None


def parse_uid_from_dumpsys(output,user):
    in_pkg=False;app_id=None;user_installed=None
    user_pattern=re.compile(r'^\s*User\s+'+re.escape(str(user))+r':\s*(.*)$')
    for raw in (output or '').splitlines():
        line=raw.rstrip()
        if re.match(r'^\s*Package \[com\.pilipala\.tvhome\]',line):
            in_pkg=True
            continue
        if not in_pkg:continue
        if re.match(r'^\s*Package \[',line) or (line and not line[0].isspace()):
            break
        if app_id is None:
            match=re.search(r'\b(?:userId|appId)=(\d+)\b',line)
            if match:app_id=int(match.group(1))
        user_match=user_pattern.match(line)
        if user_match:
            details=user_match.group(1)
            if 'installed=false' in details:user_installed=False
            elif 'installed=true' in details:user_installed=True
    if app_id is None or app_id<=0 or user_installed is False:return None
    return str(int(user)*100000+(app_id%100000))


def parse_apk_paths(output):
    paths=[]
    for raw in (output or '').splitlines():
        line=raw.strip()
        if line.startswith('package:'):paths.append(line[8:])
    return paths


def valid_apk_path(path):
    return bool(path) and '..' not in path and bool(APK_PATH_RE.fullmatch(path))


def probe_processes(shell_try):
    out=shell_try('ps','-A')
    if has_valid_ps_listing(out):return out,True
    out=shell_try('ps')
    if has_valid_ps_listing(out):return out,True
    return '',False


def list_processes(shell_try):
    out,verified=probe_processes(shell_try)
    if not verified:
        raise ProcessListUnavailableError('无法验证进程列表：ps -A 与 ps 均未返回有效的 PID 数据行。')
    return out


def resolve_current_user(shell_try):
    am_out=shell_try('am','get-current-user')
    if am_out.strip().isdigit():return am_out.strip()
    return parse_current_user(am_out,shell_try('pm','list','users'))


def resolve_package_uid(shell_try,user):
    uid=parse_uid_from_packages(shell_try('pm','list','packages','-U','--user',user,PACKAGE))
    if uid:return uid
    uid=parse_uid_from_dumpsys(shell_try('dumpsys','package',PACKAGE),user)
    if uid:return uid
    raise RuntimeError('Install Marco TV for the current user first')


def resolve_apk_path(shell_try,user):
    paths=parse_apk_paths(shell_try('pm','path','--user',user,PACKAGE))
    if not paths:
        paths=parse_apk_paths(shell_try('pm','path',PACKAGE))
    if len(paths)!=1 or not valid_apk_path(paths[0]):
        raise RuntimeError('Unexpected APK path')
    return paths[0]


def find_verified_bridge_pids(shell_try,strict=False):
    out,verified=probe_processes(shell_try)
    if not verified:return None,False
    matched=[]
    for pid in parse_bridge_pids(out):
        if not _is_positive_pid(pid):raise RuntimeError('Invalid process id')
        command=shell_try('cat','/proc/'+pid+'/cmdline').replace('\x00',' ').strip()
        if not command or 'No such file' in command or "can't open" in command:
            continue
        if not is_bridge_cmdline(command):
            if strict:raise RuntimeError('Unexpected process; not stopping')
            continue
        matched.append(pid)
    return matched,True


def stop_existing_bridges(shell_try,shell):
    pids,verified=find_verified_bridge_pids(shell_try,strict=True)
    if not verified:
        raise ProcessListUnavailableError('无法验证进程列表：ps -A 与 ps 均未返回有效的 PID 数据行，无法确认现有任务桥状态。')
    stopped=False
    for pid in pids:
        shell('kill',pid)
        stopped=True
    return stopped


def verify_bridge_started(shell_try,sleep_fn=time.sleep,attempts=10,interval=.5):
    seen_once=False;saw_valid_listing=False
    for _ in range(attempts):
        sleep_fn(interval)
        pids,verified=find_verified_bridge_pids(shell_try,strict=False)
        if not verified:
            seen_once=False
            continue
        saw_valid_listing=True
        if pids:
            if seen_once:return 'started',pids
            seen_once=True
        else:
            seen_once=False
    if not saw_valid_listing:return 'unverifiable',[]
    return 'not_running',[]


ADB_ERROR_RE=re.compile(r'^(?:adb(?:\.exe)?:|error:\s*(?:device|no devices|closed|more than one|protocol|cannot))',re.I)


def is_adb_transport_error(stderr):
    return any(ADB_ERROR_RE.match(line.strip()) for line in (stderr or '').splitlines())


def _run_adb_shell(base,command,check):
    res=subprocess.run(base+['shell',*command],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=20)
    if res.returncode!=0:
        err=(res.stderr or '').strip()
        if check:
            raise RuntimeError(err or (res.stdout or '').strip() or f'adb shell exited with {res.returncode}')
        if is_adb_transport_error(err):
            raise RuntimeError(err)
    return (res.stdout or '').strip()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',default='adb');parser.add_argument('--serial',required=True);parser.add_argument('--stop',action='store_true')
    args=parser.parse_args();base=[args.adb,'-s',args.serial]
    def shell(*command):return _run_adb_shell(base,command,True)
    def shell_try(*command):return _run_adb_shell(base,command,False)
    # Only stop processes whose complete argv identifies our fixed bridge entrypoint.
    stopped=False
    try:
        stopped=stop_existing_bridges(shell_try,shell)
    except ProcessListUnavailableError:
        if args.stop:
            raise ProcessListUnavailableError('无法验证进程列表：ps -A 与 ps 均未返回有效的 PID 数据行，无法确认任务桥是否已停止。')
    if args.stop:print('Marco TV task bridge stopped.');return
    if stopped:time.sleep(.3)
    user=resolve_current_user(shell_try)
    if not user.isdigit():raise RuntimeError('Unable to identify current user')
    uid=resolve_package_uid(shell_try,user)
    apk_path=resolve_apk_path(shell_try,user)
    launch='app_process /system/bin --nice-name='+BRIDGE_NAME+' '+BRIDGE_CLASS+' '+uid+' '+user+' </dev/null >/data/local/tmp/marcotv-task-bridge.log 2>&1'
    quoted=shlex.quote(apk_path)
    command="trap '' HUP; if command -v setsid >/dev/null 2>&1; then CLASSPATH="+quoted+' setsid '+launch+' & else CLASSPATH='+quoted+' '+launch+' & fi; sleep 0.2 2>/dev/null || sleep 1'
    shell(command)
    status,pids=verify_bridge_started(shell_try)
    if status=='unverifiable':
        raise ProcessListUnavailableError('无法验证进程列表：已发送启动命令，但 ps -A 与 ps 均未返回有效的 PID 数据行，无法确认任务桥是否运行。请在电视端打开 Marco TV > 最近任务 > 状态中心确认实际连接状态。')
    if status!='started':
        raise RuntimeError('Bridge did not start: '+shell_try('cat','/data/local/tmp/marcotv-task-bridge.log')[-1200:])
    print('Task bridge started (PID '+','.join(pids)+'). Open Marco TV > 最近任务 > 状态中心.')
    print('The helper runs locally as ADB shell, survives ordinary standby, and must be restarted after a full reboot or APK update.')


if __name__=='__main__':main()
