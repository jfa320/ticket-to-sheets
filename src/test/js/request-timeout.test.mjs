import test from 'node:test';
import assert from 'node:assert/strict';
import {withRequestTimeout} from '../../main/resources/static/request-timeout.mjs';
test('un transporte que ignora abort no mantiene bloqueada la cancelación',async()=>{
    let expire,signal,cleared=false;
    const result=withRequestTimeout(value=>{signal=value;return new Promise(()=>{});},
        {setTimer:callback=>{expire=callback;return 1;},clearTimer:()=>{cleared=true;}});
    const failure=assert.rejects(result,{name:'TimeoutError'}); await Promise.resolve(); expire(); await failure;
    assert.equal(signal.aborted,true); assert.equal(cleared,true);
});
test('abortar externamente cancela una respuesta parcial y limpia el temporizador',async()=>{
    const controller=new AbortController(); let cleared=0;
    const result=withRequestTimeout(()=>new Promise(()=>{}),{signal:controller.signal,setTimer:()=>1,clearTimer:()=>cleared++});
    const failure=assert.rejects(result,{name:'AbortError'});controller.abort();await failure;assert.equal(cleared,1);
});
test('una respuesta completada no conserva timers ni cambia su resultado',async()=>{
    let cleared=0; assert.equal(await withRequestTimeout(async()=>42,{setTimer:()=>1,clearTimer:()=>cleared++}),42);
    assert.equal(cleared,1);
});
