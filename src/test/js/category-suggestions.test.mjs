import test from 'node:test';
import assert from 'node:assert/strict';
import {loadCategories} from '../../main/resources/static/category-suggestions.mjs';

test('local categories populate suggestions without restricting free text', async () => {
    const list={children:[],replaceChildren(...values){this.children=values;}};
    const root={getElementById:()=>list,createElement:()=>({value:''})};
    await loadCategories(root,async()=>({ok:true,json:async()=>({categories:['Personal','Lácteos']})}));
    assert.deepEqual(list.children.map(value=>value.value),['Personal','Lácteos']);
});
test('malformed category responses do not replace the current suggestions', async () => {
    let replacements=0;
    const root={getElementById:()=>({replaceChildren(){replacements++;}})};
    await loadCategories(root,async()=>({ok:true,json:async()=>({categories:['Valid',null]})}));
    assert.equal(replacements,0);
});
