import test from 'node:test';
import assert from 'node:assert/strict';
import {learningRequest} from '../../main/resources/static/learning-backup.mjs';

test('backup preview and confirmation use separate requests and bind the exact reviewed version', async () => {
    const calls=[];
    const fetcher=async (url,options) => { calls.push({url,options}); return {ok:true,json:async()=>({accepted:true})}; };
    const backup={format:'ProyectoFacturas-learning',schemaVersion:1,corrections:[],brands:['Marca']};
    await learningRequest('preview',backup,fetcher);
    assert.equal(calls.length,1); assert.equal(calls[0].url,'/api/learning/preview');
    assert.deepEqual(JSON.parse(calls[0].options.body),backup);
    await learningRequest('restore',{backup,expectedVersion:'a'.repeat(64),backupHash:'b'.repeat(64)},fetcher);
    assert.equal(calls.length,2); assert.equal(JSON.parse(calls[1].options.body).expectedVersion,'a'.repeat(64));
});
test('a changed memory fails without automatically retrying a restore', async () => {
    let calls=0;
    await assert.rejects(learningRequest('restore',{},async()=> { calls++; return {ok:false,status:409,json:async()=>({message:'Volvé a revisar la vista previa.'})}; }),/vista previa/);
    assert.equal(calls,1);
});
test('invalid export JSON is never treated as a usable backup', async () => {
    await assert.rejects(learningRequest('backup',undefined,async()=>({ok:true,json:async()=>{throw Error('invalid');}})),/no es válida/);
});
