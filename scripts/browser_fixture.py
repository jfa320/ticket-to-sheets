"""Entorno E2E local: UI real + backend aislado; OCR y Sheets sintéticos, sin red externa.

Iniciar el backend en 18081, desde una carpeta temporal sin datos personales, con
app.ocr.endpoint=http://127.0.0.1:18082/ocr y health-endpoint=.../health.
Este servidor sirve la UI en 18082 y conserva únicamente datos sintéticos.
"""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
import json
from pathlib import Path
import urllib.error
import urllib.request
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / 'src/main/resources/static'
SHEET_ID = 'synthetic_e2e_spreadsheet_1234567890'


def make_receipt():
    image = Image.new('RGB',(600,420),'white')
    draw = ImageDraw.Draw(image)
    try: font = ImageFont.truetype('C:/Windows/Fonts/arial.ttf',20)
    except OSError: font = ImageFont.load_default()
    rows = [('SUPERMERCADO',20,20),('LOCAL PRUEBA',20,55),('Fecha 15/10/2026',20,90),
            ('LECHE ENTERA',20,150),('2 x',330,150),('500,00',450,150),('PAN',20,210),('600,00',450,210),('TOTAL 1600,00',20,280)]
    detections=[]
    for index,(text,x,y) in enumerate(rows):
        draw.text((x,y),text,font=font,fill='black')
        width=draw.textbbox((x,y),text,font=font)[2]-x
        detections.append({'text':text,'confidence':.55 if index==5 else .99,
            'box':[[x,y],[x+width,y],[x+width,y+24],[x,y+24]]})
    data=BytesIO();image.save(data,'JPEG')
    return image,{'text':'\n'.join(text for text,_,_ in rows),'detections':detections,'lines':[],
        'variant':'synthetic-e2e','score':100,'preview':{'width':600,'height':420,
        'imageDataUrl':'data:image/jpeg;base64,'+base64.b64encode(data.getvalue()).decode('ascii')}}


class Fixture:
    def __init__(self,backend):
        self.backend=backend
        self.responses={}
        self.writes=[]
        self.append_calls=0
        self.lookup_calls=0
        self.fail_status=0
        self.fail_corrections=0
        self.lose_append=False
        self.ocr=make_receipt()[1]
        self.config={'spreadsheetId':SHEET_ID,'spreadsheetUrl':f'https://docs.google.com/spreadsheets/d/{SHEET_ID}/edit',
            'year':2026,'headerRow':2,'monthSheets':{'10':'Octubre'},'configVersion':'fixture-v1',
            'credentialsConfigured':True,'serviceAccountEmail':'fixture@example.invalid'}


def handler_for(fixture):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def respond(self,status,payload,content_type='application/json'):
            data=payload if isinstance(payload,bytes) else json.dumps(payload,ensure_ascii=False).encode('utf-8')
            self.send_response(status);self.send_header('Content-Type',content_type+'; charset=utf-8')
            self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        def route(self):
            path=self.path.split('?',1)[0]
            body=self.rfile.read(int(self.headers.get('Content-Length','0')))
            if path=='/health':return self.respond(200,{'ocrReady':True,'ocrBusy':False})
            if path=='/ocr':return self.respond(200,fixture.ocr)
            if path=='/__test__/control' and self.command=='POST':
                data=json.loads(body)
                fixture.fail_status=int(data.get('failStatus',fixture.fail_status))
                fixture.fail_corrections=int(data.get('failCorrections',fixture.fail_corrections))
                fixture.lose_append=bool(data.get('loseAppend',fixture.lose_append))
                return self.respond(200,{'configured':True})
            if path=='/__test__/state':
                return self.respond(200,{'writes':len(fixture.writes),'appendCalls':fixture.append_calls,
                    'lookupCalls':fixture.lookup_calls,'rows':fixture.writes})
            if path=='/api/sheets/config':return self.respond(200,fixture.config)
            if path=='/api/sheets/check':return self.respond(200,{**fixture.config,'spreadsheetTitle':'Planilla sintética E2E',
                'availableSheets':['Octubre'],'suggestedMonthSheets':{'10':'Octubre'},'unavailableSheets':[]})
            if path=='/api/sheets/history':return self.respond(200,{'active':False,'rowCount':0,'productCount':0,'sheets':[],'skippedSheets':[]})
            if path=='/api/sheets/append':
                data=json.loads(body);fixture.append_calls+=1;key=data['requestId']
                if key not in fixture.responses:
                    fixture.writes.append(data['items'])
                    fixture.responses[key]={'appendedRows':len(data['items']),'duplicate':False,'sheetName':data['destination']['sheetName'],
                        'updatedRange':'Octubre!A3:H4','spreadsheetUrl':fixture.config['spreadsheetUrl']}
                if fixture.lose_append:
                    fixture.lose_append=False
                    return self.respond(503,{'message':'Respuesta perdida después de guardar en el destino sintético.'})
                return self.respond(200,fixture.responses[key])
            if path=='/api/sheets/status':
                fixture.lookup_calls+=1
                if fixture.fail_status:
                    fixture.fail_status-=1;return self.respond(503,{'message':'No se pudo confirmar el destino sintético.'})
                result=fixture.responses.get(json.loads(body)['requestId'])
                return self.respond(200,{'committed':result is not None,'result':result})
            if path.startswith('/api/'):
                if path=='/api/corrections' and self.command=='POST' and fixture.fail_corrections:
                    fixture.fail_corrections-=1;return self.respond(503,{'message':'Falla sintética al guardar la corrección.'})
                headers={name:value for name,value in self.headers.items() if name.lower() in {'content-type','x-extraction-id','x-ocr-search-budget-seconds'}}
                request=urllib.request.Request(fixture.backend+self.path,data=body if self.command!='GET' else None,headers=headers,method=self.command)
                try:
                    with urllib.request.urlopen(request,timeout=40) as response:
                        return self.respond(response.status,response.read(),response.headers.get('Content-Type','application/json').split(';')[0])
                except urllib.error.HTTPError as error:
                    return self.respond(error.code,error.read(),'application/octet-stream')
                except OSError:return self.respond(503,{'message':'Backend E2E aislado no disponible.'})
            if self.command!='GET':return self.respond(405,{'message':'Método no permitido'})
            source=(STATIC/('index.html' if path=='/' else path.lstrip('/'))).resolve()
            if not source.is_relative_to(STATIC.resolve()) or not source.is_file():return self.respond(404,{'message':'Archivo inexistente'})
            kind='text/html' if source.suffix=='.html' else 'text/css' if source.suffix=='.css' else 'text/javascript' if source.suffix in ('.js','.mjs') else 'image/svg+xml'
            return self.respond(200,source.read_bytes(),kind)
        do_GET=do_POST=do_PUT=do_DELETE=route
    return Handler


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=18082)
    parser.add_argument('--backend',default='http://127.0.0.1:18081')
    args=parser.parse_args()
    if args.backend!='http://127.0.0.1:18081':parser.error('El fixture solo admite el backend aislado 127.0.0.1:18081')
    output=ROOT/'test-data/browser-e2e';output.mkdir(parents=True,exist_ok=True)
    image,_=make_receipt();image.save(output/'receipt.png')
    image.save(output/'two-pages.pdf',save_all=True,append_images=[image],resolution=72)
    print(f'Fixture E2E: http://127.0.0.1:{args.port} (destino Sheets sintético)',flush=True)
    ThreadingHTTPServer(('127.0.0.1',args.port),handler_for(Fixture(args.backend))).serve_forever()


if __name__=='__main__':main()
