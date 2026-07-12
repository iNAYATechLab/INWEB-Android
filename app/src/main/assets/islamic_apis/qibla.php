<?php
/**
 * INWEB Islamic API — Qibla direction
 *
 * Usage: GET /api/qibla.php?lat=23.8103&lng=90.4125
 *
 * Returns JSON:
 *   { latitude, longitude, qibla_bearing_deg, distance_km, kaaba }
 *
 * Kaaba coordinates: 21.4225°N, 39.8262°E (Great Circle bearing from user).
 */
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');

$lat = filter_input(INPUT_GET, 'lat', FILTER_VALIDATE_FLOAT);
$lng = filter_input(INPUT_GET, 'lng', FILTER_VALIDATE_FLOAT);
if ($lat === false || $lng === false) {
    http_response_code(400); echo json_encode(['error' => 'lat, lng required']); exit;
}

$kaabaLat = 21.4225;
$kaabaLng = 39.8262;

$phi1 = deg2rad($lat);
$phi2 = deg2rad($kaabaLat);
$dLon = deg2rad($kaabaLng - $lng);

$y = sin($dLon) * cos($phi2);
$x = cos($phi1) * sin($phi2) - sin($phi1) * cos($phi2) * cos($dLon);
$bearing = fmod(rad2deg(atan2($y, $x)) + 360.0, 360.0);

// Haversine distance
$a = sin(($phi2 - $phi1) / 2) ** 2 +
     cos($phi1) * cos($phi2) * sin($dLon / 2) ** 2;
$c = 2 * atan2(sqrt($a), sqrt(1 - $a));
$km = 6371.0 * $c;

/* Cardinal label */
$labels = ['N','NNE','NE','ENE','E','ESE','SE','SSE','S','SSW','SW','WSW','W','WNW','NW','NNW'];
$label = $labels[(int) round($bearing / 22.5) % 16];

echo json_encode([
    'latitude'          => $lat,
    'longitude'         => $lng,
    'qibla_bearing_deg' => round($bearing, 2),
    'cardinal'          => $label,
    'distance_km'       => round($km, 2),
    'kaaba'             => ['lat' => $kaabaLat, 'lng' => $kaabaLng],
    'source'            => 'INWEB Islamic API'
], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
