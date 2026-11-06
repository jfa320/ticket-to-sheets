import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {calculateSummary, buildExports} from '../../main/resources/static/receipt-model.mjs';

const cases = JSON.parse(readFileSync(new URL('../resources/money-cases.json', import.meta.url), 'utf8'));
for (const example of cases) {
    test(`shared Java/JavaScript money: ${example.name}`, () => {
        const discounts = example.discounts.map(importe => ({importe}));
        const summary = calculateSummary(example.items, discounts);
        for (const field of ['subtotal', 'discountTotal', 'total']) assert.equal(summary[field], Number(example[field]));
        assert.equal(buildExports(example.items, discounts).total, summary.total);
    });
}

test('excluded or invalid values do not contribute to totals', () => {
    assert.deepEqual(calculateSummary([{cantidad:'1',precioUnitario:'2,01',usar:false},
        {cantidad:'invalid',precioUnitario:'9'}], [{importe:'-9',usar:false},{importe:'invalid'},{importe:'3'}]),
        {subtotal:0,discountTotal:0,total:0});
});
