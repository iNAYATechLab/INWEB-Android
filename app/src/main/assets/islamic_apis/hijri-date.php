<?php
/**
 * INWEB Islamic API — Gregorian ↔ Hijri conversion
 *
 * Usage:
 *   GET /api/hijri-date.php                        (today)
 *   GET /api/hijri-date.php?date=2026-07-12       (specific gregorian)
 *   GET /api/hijri-date.php?hijri=1447-01-15      (convert to gregorian)
 *
 * Uses the tabular arithmetic (Umm al-Qura style, ±1 day tolerance).
 */
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');

/* ---- Julian Day <-> Gregorian ---- */
function gregorianToJd(int $y, int $m, int $d): int {
    if ($m < 3) { $y -= 1; $m += 12; }
    $a = intdiv($y, 100);
    $b = 2 - $a + intdiv($a, 4);
    return (int) floor(365.25 * ($y + 4716))
         + (int) floor(30.6001 * ($m + 1))
         + $d + $b - 1524;
}
function jdToGregorian(int $jd): array {
    $a = $jd + 32044;
    $b = intdiv(4 * $a + 3, 146097);
    $c = $a - intdiv(146097 * $b, 4);
    $d = intdiv(4 * $c + 3, 1461);
    $e = $c - intdiv(1461 * $d, 4);
    $m = intdiv(5 * $e + 2, 153);
    $day = $e - intdiv(153 * $m + 2, 5) + 1;
    $month = $m + 3 - 12 * intdiv($m, 10);
    $year = 100 * $b + $d - 4800 + intdiv($m, 10);
    return [$year, $month, $day];
}

/* ---- Julian Day <-> Islamic (arithmetic) ---- */
function islamicToJd(int $y, int $m, int $d): int {
    return $d + (int) ceil(29.5 * ($m - 1))
         + ($y - 1) * 354 + intdiv(3 + 11 * $y, 30) + 1948440 - 1;
}
function jdToIslamic(int $jd): array {
    $jd = $jd + 1;
    $y = (int) floor((30 * ($jd - 1948440) + 10646) / 10631);
    $m = (int) min(12, ceil(($jd - (29 + islamicToJd($y, 1, 1))) / 29.5) + 1);
    $d = ($jd - islamicToJd($y, $m, 1)) + 1;
    return [$y, $m, $d];
}

$MONTHS_HIJRI = [
    'Muharram', 'Safar', 'Rabiʿ al-Awwal', 'Rabiʿ al-Thani',
    'Jumada al-Ula', 'Jumada al-Thani', 'Rajab', 'Shaʿban',
    'Ramadan', 'Shawwal', 'Dhu al-Qiʿdah', 'Dhu al-Hijjah'
];
$MONTHS_HIJRI_AR = [
    'محرم', 'صفر', 'ربيع الأول', 'ربيع الثاني',
    'جمادى الأولى', 'جمادى الثانية', 'رجب', 'شعبان',
    'رمضان', 'شوال', 'ذو القعدة', 'ذو الحجة'
];

$hijriParam = $_GET['hijri'] ?? null;
$gregParam  = $_GET['date']  ?? date('Y-m-d');

if ($hijriParam) {
    if (!preg_match('/^(\d{3,4})-(\d{1,2})-(\d{1,2})$/', $hijriParam, $m)) {
        http_response_code(400); echo json_encode(['error' => 'hijri must be YYYY-MM-DD']); exit;
    }
    $jd = islamicToJd((int)$m[1], (int)$m[2], (int)$m[3]);
    [$gy, $gm, $gd] = jdToGregorian($jd);
    echo json_encode([
        'input' => ['hijri' => $hijriParam],
        'gregorian' => sprintf('%04d-%02d-%02d', $gy, $gm, $gd),
        'source' => 'INWEB Islamic API (tabular)'
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
    exit;
}

if (!preg_match('/^(\d{4})-(\d{1,2})-(\d{1,2})$/', $gregParam, $m)) {
    http_response_code(400); echo json_encode(['error' => 'date must be YYYY-MM-DD']); exit;
}
$jd = gregorianToJd((int)$m[1], (int)$m[2], (int)$m[3]);
[$hy, $hm, $hd] = jdToIslamic($jd);

echo json_encode([
    'input' => ['gregorian' => $gregParam],
    'hijri' => [
        'year'       => $hy,
        'month'      => $hm,
        'day'        => $hd,
        'formatted'  => sprintf('%04d-%02d-%02d', $hy, $hm, $hd),
        'month_name' => $MONTHS_HIJRI[$hm - 1],
        'month_name_ar' => $MONTHS_HIJRI_AR[$hm - 1],
    ],
    'source' => 'INWEB Islamic API (tabular)'
], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
