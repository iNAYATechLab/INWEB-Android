<?php
/**
 * INWEB Islamic API — Zakat calculator
 *
 * Usage:
 *   GET /api/zakat.php?cash=100000&gold_g=50&silver_g=200&business=50000
 *
 * Params (all optional, default 0):
 *   cash        — cash in hand + bank
 *   gold_g      — gold weight in grams
 *   silver_g    — silver weight in grams
 *   business    — business inventory value
 *   liabilities — debts to subtract
 *   gold_ppg    — gold price per gram (default 7500 BDT)
 *   silver_ppg  — silver price per gram (default 100 BDT)
 *   currency    — display currency label (default BDT)
 *
 * Nisab: 87.48 g gold OR 612.36 g silver — the lower one applies.
 * Zakat rate: 2.5 %.
 */
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');

$cash        = (float) ($_GET['cash']        ?? 0);
$goldG       = (float) ($_GET['gold_g']      ?? 0);
$silverG     = (float) ($_GET['silver_g']    ?? 0);
$business    = (float) ($_GET['business']    ?? 0);
$liabilities = (float) ($_GET['liabilities'] ?? 0);
$goldPpg     = (float) ($_GET['gold_ppg']    ?? 7500);
$silverPpg   = (float) ($_GET['silver_ppg']  ?? 100);
$currency    = $_GET['currency'] ?? 'BDT';

$goldValue   = $goldG   * $goldPpg;
$silverValue = $silverG * $silverPpg;

$assets    = $cash + $goldValue + $silverValue + $business;
$zakatable = max(0, $assets - $liabilities);

$nisabGold   = 87.48  * $goldPpg;
$nisabSilver = 612.36 * $silverPpg;
$nisab       = min($nisabGold, $nisabSilver);   // Use lower per common opinion

$reached = $zakatable >= $nisab;
$zakat   = $reached ? round($zakatable * 0.025, 2) : 0.0;

echo json_encode([
    'inputs' => [
        'cash' => $cash, 'gold_g' => $goldG, 'silver_g' => $silverG,
        'business' => $business, 'liabilities' => $liabilities,
        'gold_price_per_gram' => $goldPpg, 'silver_price_per_gram' => $silverPpg,
        'currency' => $currency
    ],
    'calculations' => [
        'gold_value'          => $goldValue,
        'silver_value'        => $silverValue,
        'total_assets'        => $assets,
        'zakatable_amount'    => $zakatable,
        'nisab_gold'          => $nisabGold,
        'nisab_silver'        => $nisabSilver,
        'nisab_applied'       => $nisab,
    ],
    'result' => [
        'zakat_due'      => $zakat,
        'currency'       => $currency,
        'nisab_reached'  => $reached,
        'rate_percent'   => 2.5,
    ],
    'notes' => 'Educational estimate only. Consult a scholar for authoritative fatwa. Nisab uses the lower of 87.48 g gold / 612.36 g silver.',
    'source' => 'INWEB Islamic API'
], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
