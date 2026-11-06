export async function loadCategories(root = document, fetcher = fetch) {
    const list = root.getElementById('categorySuggestions');
    if (!list) return;
    const response = await fetcher('/api/categories');
    if (!response.ok) return;
    const data = await response.json();
    if (!Array.isArray(data.categories) || data.categories.length > 500
            || data.categories.some(value => typeof value !== 'string' || !value.trim() || value.length > 200)) return;
    list.replaceChildren(...data.categories.map(value => {
        const option = root.createElement('option'); option.value = value; return option;
    }));
}
