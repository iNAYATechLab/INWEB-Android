<?php
/**
 * INWEB Islamic API — Prayer Times
 *
 * Usage:
 *   GET  /api/prayer-times.php?lat=23.8103&lng=90.4125&method=1&date=YYYY-MM-DD
 *
 * Params:
 *   lat, lng   — required. Latitude/longitude in decimal degrees.
 *   method     — optional int (default 1 = University of Islamic Sciences, Karachi)
 *                Supported: 1=Karachi, 2=ISNA, 3=MWL, 4=Makkah, 5=Egypt
 *   date       — optional YYYY-MM-DD (default: today, server time)
 *
 * Returns JSON:
 *   { date, method, latitude, longitude, timings:{ fajr, sunrise, dhuhr, asr, maghrib, isha } }
 *
 * The math is a self-contained implementation of the classic
 * astronomical prayer-time algorithm (no external service, works offline).
 */
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');

$lat    = filter_input(INPUT_GET, 'lat',    FILTER_VALIDATE_FLOAT);
$lng    = filter_input(INPUT_GET, 'lng',    FILTER_VALIDATE_FLOAT);
$method = (int) ($_GET['method'] ?? 1);
$date   = $_GET['date'] ?? date('Y-m-d');

if ($lat === false || $lng === false) {
    http_response_code(400);
    echo json_encode(['error' => 'lat and lng are required (decimal degrees)']);
    exit;
}

/* --- Calculation-method Fajr / Isha angles ------------------------- */
$methods = [
    1 => ['name' => 'University of Islamic Sciences, Karachi', 'fajr' => 18.0, 'isha' => 18.0],
    2 => ['name' => 'ISNA (North America)',                     'fajr' => 15.0, 'isha' => 15.0],
    3 => ['name' => 'Muslim World League',                      'fajr' => 18.0, 'isha' => 17.0],
    4 => ['name' => 'Umm Al-Qura, Makkah',                      'fajr' => 18.5, 'isha' => 90.0],  // 90 = 90 minutes after Maghrib
    5 => ['name' => 'Egyptian General Authority',               'fajr' => 19.5, 'isha' => 17.5],
];
if (!isset($methods[$method])) $method = 1;

$ts   = strtotime($date . ' 12:00:00');
if ($ts === false) { http_response_code(400); echo json_encode(['error' => 'bad date']); exit; }

/* --- Astronomical helpers ----------------------------------------- */
function deg2radLocal(float $d): float { return $d * M_PI / 180.0; }
function rad2degLocal(float $r): float { return $r * 180.0 / M_PI; }

function julianDay(int $ts): float {
    return $ts / 86400.0 + 2440587.5;
}

function sunPosition(float $jd): array {
    $D = $jd - 2451545.0;
    $g = fmod(357.529 + 0.98560028 * $D, 360.0);
    $q = fmod(280.459 + 0.98564736 * $D, 360.0);
    $L = fmod($q + 1.915 * sin(deg2radLocal($g)) + 0.020 * sin(deg2radLocal(2 * $g)), 360.0);
    $e = 23.439 - 0.00000036 * $D;
    $RA = rad2degLocal(atan2(cos(deg2radLocal($e)) * sin(deg2radLocal($L)),
                             cos(deg2radLocal($L)))) / 15.0;
    $decl = rad2degLocal(asin(sin(deg2radLocal($e)) * sin(deg2radLocal($L))));
    $EqT = $q / 15.0 - $RA;
    if ($EqT > 12)  $EqT -= 24;
    if ($EqT < -12) $EqT += 24;
    return ['decl' => $decl, 'eqt' => $EqT];
}

/** Solve for time (hours after solar noon) when sun altitude = -angle */
function timeForAngle(float $angle, float $lat, float $decl): float {
    $cosT = (-sin(deg2radLocal($angle)) - sin(deg2radLocal($lat)) * sin(deg2radLocal($decl))) /
            (cos(deg2radLocal($lat)) * cos(deg2radLocal($decl)));
    if ($cosT > 1)  return NAN;
    if ($cosT < -1) return NAN;
    return rad2degLocal(acos($cosT)) / 15.0;
}

function asrTime(float $lat, float $decl): float {
    // Standard (Shafi) — shadow factor = 1
    $A = atan(1.0 / (1.0 + tan(deg2radLocal(abs($lat - $decl)))));
    $altitude = rad2degLocal(atan(1.0 / tan($A)));
    return timeForAngle(-$altitude, $lat, $decl);
}

/* --- Compute the six timings -------------------------------------- */
$jd  = julianDay($ts);
$sun = sunPosition($jd);
$decl = $sun['decl']; $eqt = $sun['eqt'];

$timezoneOffset = date('Z', $ts) / 3600.0;
$noon = 12 - $eqt - $lng / 15.0 + $timezoneOffset;   // solar noon (Dhuhr)

$fajr    = $noon - timeForAngle($methods[$method]['fajr'], $lat, $decl);
$sunrise = $noon - timeForAngle(0.833, $lat, $decl);
$dhuhr   = $noon + (2.0 / 60.0);
$asr     = $noon + asrTime($lat, $decl);
$maghrib = $noon + timeForAngle(0.833, $lat, $decl);

if ($methods[$method]['isha'] > 45) {
    // Umm al-Qura style — fixed minutes after Maghrib.
    $isha = $maghrib + $methods[$method]['isha'] / 60.0;
} else {
    $isha = $noon + timeForAngle($methods[$method]['isha'], $lat, $decl);
}

function fmtTime(float $h): string {
    if (is_nan($h)) return '—';
    $h = fmod(($h + 24), 24);
    $hours   = (int) floor($h);
    $minutes = (int) round(($h - $hours) * 60);
    if ($minutes == 60) { $hours = ($hours + 1) % 24; $minutes = 0; }
    return sprintf('%02d:%02d', $hours, $minutes);
}

echo json_encode([
    'date'      => $date,
    'method'    => ['id' => $method, 'name' => $methods[$method]['name']],
    'latitude'  => $lat,
    'longitude' => $lng,
    'timezone_offset_hours' => $timezoneOffset,
    'timings' => [
        'fajr'    => fmtTime($fajr),
        'sunrise' => fmtTime($sunrise),
        'dhuhr'   => fmtTime($dhuhr),
        'asr'     => fmtTime($asr),
        'maghrib' => fmtTime($maghrib),
        'isha'    => fmtTime($isha),
    ],
    'source' => 'INWEB Islamic API — self-contained (offline)',
], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
