"""Mide el recorrido real Java + PaddleOCR en una instancia local aislada."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ocr'))
from evaluate import load_manifest, run_evaluation, extract_ocr_response, compare_reports


def assess_target(report, seconds=10):
    violations=[case['id'] for case in report['cases'] if case['status']!='ok'
        or case.get('request_seconds',float('inf'))>=seconds]
    return {'target_seconds':seconds,'passed':bool(report['cases']) and not violations,'cases_over_target_or_failed':len(violations),
        'evaluated_cases':len(report['cases'])}


def free_port(port):
    with socket.socket() as server:server.bind(('127.0.0.1',port))


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest',type=Path)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--budget',type=int,default=8,choices=range(5,181))
    parser.add_argument('--target-seconds',type=float,default=10)
    parser.add_argument('--baseline-report',type=Path)
    parser.add_argument('--brands',type=Path,help='Catálogo opcional congelado para comparar ambos recorridos sin memoria personal')
    args=parser.parse_args(argv)
    if not 0<args.target_seconds<3600:parser.error('El objetivo debe estar entre 0 y 3600 segundos')
    cases=load_manifest(args.manifest)
    jar=ROOT/'target/facturas-ocr-0.0.1-SNAPSHOT.jar'
    if not jar.is_file():parser.error('Ejecutá mvn package antes del benchmark')
    free_port(18083);free_port(18084)
    output=(args.output or ROOT/'logs'/('real-ocr-'+datetime.now().strftime('%Y%m%d-%H%M%S'))).resolve()
    output.mkdir(parents=True,exist_ok=False); (output/'data').mkdir()
    if args.brands:shutil.copyfile(args.brands,output/'data/brands.json')
    environment={**os.environ,'APP_OCR_DEBUG':'false','OCR_BIND_HOST':'127.0.0.1','OCR_BIND_PORT':'18084'}
    java=environment.get('APP_JAVA') or (str(Path(environment['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java'))
        if environment.get('JAVA_HOME') else 'java')
    temporary=output/'java-http-local-temporary-directory';temporary.mkdir()
    children=[];logs=[]
    flags={'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {}
    try:
        for name,command,cwd in [
            ('ocr',[sys.executable,str(ROOT/'ocr/service.py')],ROOT/'ocr'),
            ('backend',[java,f'-Djdk.net.unixdomain.tmpdir={temporary}','-jar',str(jar),'--server.address=127.0.0.1',
                '--server.port=18083','--app.ocr.endpoint=http://127.0.0.1:18084/ocr','--app.ocr.health-endpoint=http://127.0.0.1:18084/health'],output)]:
            stream=(output/f'{name}.log').open('w',encoding='utf-8');logs.append(stream)
            children.append(subprocess.Popen(command,cwd=cwd,env=environment,stdout=stream,stderr=subprocess.STDOUT,**flags))
        started=time.monotonic()
        while True:
            if any(child.poll() is not None for child in children):raise RuntimeError('Un servicio terminó; revisá los logs del workspace aislado')
            try:
                with urllib.request.urlopen('http://127.0.0.1:18084/health',timeout=1) as response:ready=json.load(response)['ocrReady']
                with urllib.request.urlopen('http://127.0.0.1:18083/api/categories',timeout=1) as response:response.read()
                if ready:break
            except (OSError,ValueError):pass
            if time.monotonic()-started>90:raise RuntimeError('Los servicios no quedaron listos a tiempo')
            time.sleep(.15)
        index=0
        def progress(case):
            nonlocal index
            index+=1;print(f'Ticket {index}/{len(cases)}: {case["status"]}, {case["elapsed_seconds"]:.2f} s',flush=True)
        report=run_evaluation(cases,'http://127.0.0.1:18083/api/receipts/extract',
            extractor=lambda image,url,timeout:extract_ocr_response(image,url,timeout,budget_seconds=args.budget),progress=progress)
        report['latency_target']=assess_target(report,args.target_seconds)
        report['ocr_budget_seconds']=args.budget
        if args.baseline_report:report['comparison']=compare_reports(report,json.loads(args.baseline_report.read_text(encoding='utf-8-sig')))
        (output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({'latency':report['summary']['latency_seconds'],'target':report['latency_target']},ensure_ascii=False))
        print(f'Informe privado: {output / "report.json"}')
        return 0 if report['latency_target']['passed'] else 1
    finally:
        for child in reversed(children):
            if child.poll() is not None:continue
            if os.name=='nt':subprocess.run(['taskkill','/PID',str(child.pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,**flags)
            else:child.terminate()
            try:child.wait(timeout=5)
            except subprocess.TimeoutExpired:child.kill();child.wait()
        for stream in logs:stream.close()


if __name__=='__main__':raise SystemExit(main())
