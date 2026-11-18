/** สร้างเลขบัตร 13 หลักที่ checksum ถูกต้องจาก 12 หลักแรก */
export function withChecksum(first12: string): string {
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += Number(first12[i]) * (13 - i);
  return first12 + ((11 - (sum % 11)) % 10);
}

