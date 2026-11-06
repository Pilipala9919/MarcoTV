# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Manual physical fault cases. Each invocation requires a new optical QR and TV approval."""
import argparse
import datetime
import getpass
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'remote'))
import marco_remote as cli
from tls_link import receive, send

LAB = 'com.pilipala.tvhome.focuslab'
COMPONENT = LAB+'/com.pilipala.tvhome.MainActivity'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('case',choices=['local','replay','stale','revoke','sleep','recreate','network','idle'])
    p.add_argument('--adb',required=True)
    p.add_argument('--serial',required=True)
    args = p.parse_args()
    def shell(*parts):
        return subprocess.check_output([args.adb,'-s',args.serial,'shell',*parts],timeout=8,text=True,errors='replace')
    result = {'case':args.case, 'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'outcome':'inconclusive'}
    folder = ROOT/'private/focus-witness'
    folder.mkdir(parents=True,exist_ok=True)
    out = folder/(args.case+'-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')+'.json')
    session = cli.pair(getpass.getpass('Paste the optically scanned TV QR here (hidden): ').strip())
    try:
        _, result['initial_position'] = session.state()
        if args.case in ('local','stale','replay'):
            if args.case == 'local':
                print('Hold the physical remote ready. Press an arrow once WHEN you see NOW.',flush=True)
                time.sleep(2)
            nonce,initial = session.state()
            if args.case == 'local':
                print('NOW',flush=True)
                time.sleep(.70)
            if args.case == 'stale':
                time.sleep(1.2)
            if args.case == 'replay':
                send(session.sock,f'{session.sid} {session.seq} STEP {nonce} RIGHT')
                response = receive(session.sock)
            else:
                response = session.request('STEP '+nonce+' RIGHT')
            status = response.split()[0]
            if status not in {'REPLAY','STALE_FOCUS','STALE_CHALLENGE','COMMAND_EXPIRED','MOVED','NO_CHANGE','STALE_LAYOUT'}:
                raise ValueError('Unexpected response')
            result['decision'] = status
            expected = {'local':'STALE_FOCUS','stale':'STALE_CHALLENGE','replay':'REPLAY'}[args.case]
            result['expected_decision_observed'] = status == expected
            result['outcome'] = 'decision observed' if status == expected else 'inconclusive'
            if args.case == 'replay':
                try: receive(session.sock,timeout=3)
                except EOFError: result['eof_after_replay'] = True
        else:
            start = time.monotonic()
            prior_pid = shell('pidof',LAB).strip()
            if args.case == 'recreate':
                if not prior_pid or COMPONENT not in shell('dumpsys','activity','activities'):
                    raise RuntimeError('Expected lab activity')
                # Destroy the lab task and create a new Activity without force-stopping its process.
                shell('am','start','-W','--activity-new-task','--activity-clear-task','-n',COMPONENT)
                result['process_survived_task_replacement'] = prior_pid == shell('pidof',LAB).strip()
            elif args.case == 'idle':
                time.sleep(16)
            else:
                print({'revoke':'Open TV desktop settings with the physical remote.',
                       'sleep':'Put the TV to sleep with the physical power button.',
                       'network':'Interrupt ONLY the TV network outside its UI (e.g. unplug its Ethernet cable).'}[args.case],flush=True)
            until = start+45
            while time.monotonic() < until:
                try:
                    if session.request('PING') != 'PONG': break
                except (OSError,EOFError,TimeoutError) as ended:
                    result['windows_connection_observation'] = type(ended).__name__
                    result['seconds_to_observation'] = round(time.monotonic()-start,3)
                    result['outcome'] = 'client observed loss; verify physical cause and TV log'
                    break
                time.sleep(.5)
        if args.case != 'network':
            result['lab_process_alive_after'] = bool(shell('pidof',LAB).strip())
            power = shell('dumpsys','power')
            result['tv_asleep_after'] = 'mWakefulness=Asleep' in power
    finally:
        session.sock.close()
        out.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        print('Review observations:',out,flush=True)


if __name__ == '__main__':
    try: main()
    except (KeyboardInterrupt,OSError,TimeoutError,EOFError,RuntimeError,ValueError,KeyError,TypeError):
        sys.exit('Physical fault test incomplete. Inspect saved observations.')
