// Aritmética decimal exacta. Number se usa únicamente al presentar el resultado.
export const zero = () => ({value: 0n, scale: 0});
const power = scale => 10n ** BigInt(scale);

export function decimal(text) {
    const match = /^([+-]?)(\d*)(?:\.(\d*))?(?:e([+-]?\d+))?$/i.exec(text);
    if (!match || !(match[2] || match[3])) return null;
    let value = BigInt((match[2] || '0') + (match[3] || ''));
    let scale = (match[3]?.length || 0) - Number(match[4] || 0);
    if (scale < 0) { value *= power(-scale); scale = 0; }
    return {value: match[1] === '-' ? -value : value, scale};
}

export function add(a, b) {
    const scale = Math.max(a.scale, b.scale);
    return {value: a.value * power(scale - a.scale) + b.value * power(scale - b.scale), scale};
}

export function multiply(a, b) {
    return {value: a.value * b.value, scale: a.scale + b.scale};
}

export function roundMoney(amount) {
    if (amount.scale <= 2) return {value: amount.value * power(2 - amount.scale), scale: 2};
    const divisor = power(amount.scale - 2);
    const sign = amount.value < 0n ? -1n : 1n;
    const absolute = amount.value * sign;
    return {value: sign * (absolute / divisor + (absolute % divisor * 2n >= divisor ? 1n : 0n)), scale: 2};
}

export function toNumber(amount) {
    const negative = amount.value < 0n;
    const digits = (negative ? -amount.value : amount.value).toString().padStart(amount.scale + 1, '0');
    const text = amount.scale ? `${digits.slice(0, -amount.scale)}.${digits.slice(-amount.scale)}` : digits;
    return Number(`${negative ? '-' : ''}${text}`);
}
