/** Truncate by UTF-16 code units without leaving a dangling high surrogate. */
export function truncateUtf16(value: string, maximum: number): string {
  if (value.length <= maximum) return value;
  let end = Math.max(0, maximum);
  const last = end > 0 ? value.charCodeAt(end - 1) : 0;
  if (last >= 0xd800 && last <= 0xdbff) end--;
  return value.slice(0, end);
}
