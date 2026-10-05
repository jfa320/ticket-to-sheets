import {toDateInputValue} from './receipt-model.mjs';

export const MONTH_NAMES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
    'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];

export function receiptPeriod(items) {
    const selected = items.filter(item => item.usar !== false);
    if (!selected.length) return {code: 'empty', message: 'Seleccioná al menos una fila para elegir el destino.'};
    const dates = selected.map(item => toDateInputValue(item.fecha));
    if (dates.some(date => !date)) return {code: 'invalid', message: 'Completá las fechas de las filas seleccionadas para proponer el mes.'};
    const periods = new Set(dates.map(date => date.slice(0, 7)));
    if (periods.size !== 1) return {code: 'mixed', message: 'Las filas seleccionadas tienen fechas de distintos meses. Elegí un destino o seleccioná las filas de un solo mes.'};
    const [year, month] = [...periods][0].split('-').map(Number);
    return {code: 'valid', year, month, label: `${MONTH_NAMES[month - 1]} ${year}`};
}

export function destinationLabel(sheetName, year) {
    return /\d{4}/.test(sheetName) ? sheetName : `${sheetName} ${year}`;
}

export function createMonthlyDestination() {
    let manualSheet = '';
    let sourceKey = '';
    return {
        choose(sheetName) { manualSheet = String(sheetName || ''); },
        reset() { manualSheet = ''; },
        resolve(config, availableSheets, items, suggestedMonthSheets = {}) {
            const nextSource = JSON.stringify([config?.spreadsheetId, config?.year, config?.headerRow, config?.configVersion]);
            if (nextSource !== sourceKey) { sourceKey = nextSource; manualSheet = ''; }
            const period = receiptPeriod(items);
            if (!config?.spreadsheetId || !Number.isInteger(config.year)) {
                return {mode: 'automatic', sheetName: '', label: '', period, message: 'Configurá la planilla y confirmá su año para elegir el destino.'};
            }
            const monthSheet = period.code === 'valid'
                ? (config.monthSheets?.[String(period.month)] || suggestedMonthSheets[String(period.month)] || '') : '';
            if (manualSheet) {
                if (!availableSheets.includes(manualSheet)) {
                    return {mode: 'manual', sheetName: '', label: '', period, message: 'La pestaña elegida ya no está disponible. Elegí otro destino.'};
                }
                let warning = '';
                if (period.code === 'valid' && (period.year !== config.year || monthSheet !== manualSheet)) {
                    warning = ` La fecha del ticket corresponde a ${period.label}; revisá que el destino elegido sea el que querés usar.`;
                } else if (period.code !== 'valid') warning = ` ${period.message}`;
                return {mode: 'manual', sheetName: manualSheet, label: destinationLabel(manualSheet, config.year), period,
                    message: `Destino elegido manualmente.${warning}`};
            }
            if (period.code !== 'valid') return {mode: 'automatic', sheetName: '', label: '', period, message: period.message};
            if (period.year !== config.year) return {mode: 'automatic', sheetName: '', label: '', period,
                message: `El ticket es de ${period.year} y la planilla está configurada para ${config.year}. Elegí el destino manualmente o cambiá la planilla en Configuración.`};
            if (!monthSheet || !availableSheets.includes(monthSheet)) return {mode: 'automatic', sheetName: '', label: '', period,
                message: `No hay una pestaña disponible asociada a ${period.label}. Elegí un destino o revisá las asociaciones en Configuración.`};
            return {mode: 'automatic', sheetName: monthSheet, label: destinationLabel(monthSheet, config.year), period,
                message: `Mes propuesto por la fecha del ticket: ${period.label}. Podés cambiar el destino.`};
        }
    };
}
