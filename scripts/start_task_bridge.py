# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Start or stop Marco TV's local task bridge using an already authorized ADB connection."""
import argparse,re,shlex,subprocess,time

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',default='adb');parser.add_argument('--serial',required=True);parser.add_argument('--stop',action='store_true')
    args=parser.parse_args();base=[args.adb,'-s',args.serial]
    def shell(*command):
        return subprocess.check_output(base+['shell',*command],text=True,encoding='utf-8',errors='replace',timeout=20).strip()
    # Only stop processes whose complete argv identifies our fixed bridge entrypoint.
    for pid in shell('pidof','marcotv-task-bridge').split() if 'marcotv-task-bridge' in shell('ps','-A') else []:
        if not pid.isdigit():raise RuntimeError('Invalid process id')
        command=shell('cat','/proc/'+pid+'/cmdline').replace('\x00',' ')
        if 'com.pilipala.tvhome.TaskBridgeServer' not in command and command.strip()!='marcotv-task-bridge':raise RuntimeError('Unexpected process; not stopping')
        shell('kill',pid)
    if args.stop:print('Marco TV task bridge stopped.');return
    user=shell('am','get-current-user')
    if not user.isdigit():raise RuntimeError('Unable to identify current user')
    package=shell('pm','list','packages','-U','--user',user,'com.pilipala.tvhome')
    match=re.search(r'^package:com\.pilipala\.tvhome uid:(\d+)$',package,re.M)
    if not match:raise RuntimeError('Install Marco TV for the current user first')
    path=shell('pm','path','--user',user,'com.pilipala.tvhome')
    paths=[p.removeprefix('package:') for p in path.splitlines() if p.startswith('package:')]
    if len(paths)!=1 or not paths[0].startswith('/data/app/') or not paths[0].endswith('.apk'):raise RuntimeError('Unexpected APK path')
    command='CLASSPATH='+shlex.quote(paths[0])+' app_process /system/bin --nice-name=marcotv-task-bridge com.pilipala.tvhome.TaskBridgeServer '+match[1]+' '+user+' </dev/null >/data/local/tmp/marcotv-task-bridge.log 2>&1 &'
    shell(command)
    started=False
    for _ in range(10):
        time.sleep(.5)
        if 'marcotv-task-bridge' in shell('ps','-A'):started=True;break
    if not started:
        raise RuntimeError('Bridge did not start: '+shell('cat','/data/local/tmp/marcotv-task-bridge.log')[-1200:])
    print('Task bridge started. Open Marco TV > 最近任务与双击 Home > 检查任务服务.')
    print('The helper runs locally as ADB shell, survives ordinary standby, and must be restarted after a full reboot or APK update.')

if __name__=='__main__':main()
