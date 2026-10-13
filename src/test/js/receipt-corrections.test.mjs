import test from 'node:test';
import assert from 'node:assert/strict';
import {createCorrectionSaver} from '../../main/resources/static/receipt-corrections.mjs';

const item = () => ({descripcion: 'Jabon OCR', marca: 'Genérico', categoria: 'Otros', lugarDeCompra: 'Comercio prueba',
    firma: 'jabon neutro 500g', usar: true, precioUnitario: '1000,00', cantidad: '1'});

test('edits from an unknown brand are saved automatically after a short debounce with full context', async () => {
    const row = item(), sent = [], timers = [];
    const saver = createCorrectionSaver({items: [row]}, {
        save: async payload => { sent.push(payload); return {saved: payload.corrections.length}; },
        setTimer: (callback, delay) => { timers.push({callback, delay}); return timers.length; }, clearTimer() {}
    });
    row.descripcion = 'Jabón neutro 500g'; saver.schedule();
    row.marca = 'Marca Prueba'; saver.schedule();
    assert.equal(timers.at(-1).delay, 600);
    await timers.at(-1).callback();
    assert.equal(sent.length, 1);
    assert.deepEqual(sent[0], {store: 'Comercio prueba', corrections: [{firma: 'jabon neutro 500g',
        descripcion: 'Jabón neutro 500g', marca: 'Marca Prueba', categoria: 'Otros', marcaOriginal: 'Genérico'}]});
    await saver.flush();
    assert.equal(sent.length, 1);
});

test('description and category edits are saved even when the brand remains generic', async () => {
    const row = item(), sent = [];
    const saver = createCorrectionSaver({items: [row]}, {save: async p => { sent.push(p); return {saved: 1}; }});
    row.descripcion = 'Jabón neutro'; row.categoria = 'Limpieza';
    assert.equal(await saver.flush(), true);
    assert.equal(sent[0].corrections[0].marca, 'Genérico');
    assert.equal(sent[0].corrections[0].categoria, 'Limpieza');
});

test('price-only changes and excluded rows do not train product identity', async () => {
    const row = item(), sent = [];
    const saver = createCorrectionSaver({items: [row]}, {save: async p => { sent.push(p); return {saved: 1}; }});
    row.precioUnitario = '2000,00'; await saver.flush();
    row.descripcion = 'Jabón nuevo'; row.usar = false; await saver.flush();
    assert.equal(sent.length, 0);
});

test('failed saves remain pending and retries save the latest edits', async () => {
    const row = item(), sent = [], states = [];
    const saver = createCorrectionSaver({items: [row]}, {
        save: async p => { sent.push(p); if (sent.length === 1) throw Error('Falla simulada'); return {saved: 1}; },
        onStatus: state => states.push(state)
    });
    row.marca = 'Marca Prueba';
    assert.equal(await saver.flush(), false);
    row.descripcion = 'Jabón corregido';
    assert.equal(await saver.flush(), true);
    assert.equal(sent[1].corrections[0].descripcion, 'Jabón corregido');
    assert.equal(states.at(-1), 'saved');
});

test('edits during an in-flight save are serialized and remain persisted', async () => {
    const row = item(), sent = [];
    let complete;
    const saver = createCorrectionSaver({items: [row]}, {save: p => {
        sent.push(p);
        return sent.length === 1 ? new Promise(resolve => { complete = resolve; }) : Promise.resolve({saved: 1});
    }});
    row.marca = 'Marca Prueba';
    const first = saver.flush(); await Promise.resolve();
    row.descripcion = 'Jabón corregido'; const second = saver.flush();
    assert.equal(first, second); complete({saved: 1}); await second;
    assert.equal(sent.length, 2);
    assert.equal(sent[1].corrections[0].descripcion, 'Jabón corregido');
});
