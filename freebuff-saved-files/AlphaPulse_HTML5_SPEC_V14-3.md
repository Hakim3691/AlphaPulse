# AlphaPulse HTML5 Implementation Reference (Version 14.3)

```html
<meta name='viewport' content='width=device-width, initial-scale=1'/><!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>AlphaPulse | Institutional Quantitative Screener & Predictor</title>
  <script src="https://cdn.tailwindcss.com"></script>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <script src="https://unpkg.com/lucide@latest"></script>
  <script>
    tailwind.config = {
      theme: {
        extend: {
          colors: {
            brand: {
              dark: '#080a0f',
              surface: '#101520',
              card: '#161d2b',
              border: '#232d42',
              accent: '#3b82f6',
              neural: '#8b5cf6',
              bullish: '#10b981',
              bearish: '#ef4444',
            }
          }
        }
      }
    }
  </script>
  <style>
    @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;700&family=Plus+Jakarta+Sans:wght@400;600;700&display=swap');
    body { font-family: 'Plus Jakarta Sans', sans-serif; background-color: #080a0f; }
    .mono { font-family: 'JetBrains Mono', monospace; }
    details > summary { list-style: none; }
    details > summary::-webkit-details-marker { display: none; }
    details[open] > summary .accordion-chevron { transform: rotate(180deg); }
  </style>
</head>
<body class="text-slate-100 min-h-screen flex flex-col antialiased selection:bg-brand-accent selection:text-white">

  <!-- Header -->
  <header class="border-b border-brand-border bg-brand-surface/90 backdrop-blur sticky top-0 z-50">
    <div class="max-w-7xl mx-auto px-4 py-2.5 flex flex-wrap items-center justify-between gap-2">
      <div class="flex items-center space-x-3">
        <div class="p-2 rounded-lg bg-blue-500/10 text-brand-accent border border-blue-500/20">
          <i data-lucide="shield-check" class="w-5 h-5"></i>
        </div>
        <div>
          <span class="font-bold text-lg tracking-tight text-white flex items-center gap-2">
            AlphaPulse <span class="text-xs px-2 py-0.5 rounded bg-brand-accent/20 text-brand-accent mono border border-brand-accent/30">PRO QUANT V14.3 TRI-FACTOR</span>
          </span>
        </div>
      </div>

      <!-- Session Elapsed Timer, Readiness Horizon Badges & Status Bar -->
      <div class="flex flex-wrap items-center gap-2 sm:gap-3 text-xs mono">
        <div class="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-brand-card border border-brand-border text-slate-300" title="Elapsed Time Since Session Start">
          <i data-lucide="clock" class="w-3.5 h-3.5 text-brand-accent"></i>
          <span class="text-[10px] text-slate-400 uppercase">Session:</span>
          <span id="sessionElapsedTimer" class="font-bold text-white">00:00:00</span>
        </div>

        <div class="flex items-center gap-1 text-[10px] font-bold">
          <span id="badgeLTF" class="px-2 py-1 rounded bg-slate-800/80 text-slate-500 border border-brand-border transition-colors" title="Ready after 2x 1m validations AND 1x 5m validation">LTF Ready</span>
          <span id="badgeMTF" class="px-2 py-1 rounded bg-slate-800/80 text-slate-500 border border-brand-border transition-colors" title="Ready after 2x 15m validations AND 1x 30m validation">MTF Ready</span>
          <span id="badgeHTF" class="px-2 py-1 rounded bg-slate-800/80 text-slate-500 border border-brand-border transition-colors" title="Ready after 2x 30m validations AND 1x 1h validation">HTF Ready</span>
        </div>

        <button id="headerRefreshBtn" type="button" class="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-brand-card hover:bg-slate-800 text-slate-300 border border-brand-border transition" title="Manual Refresh">
          <i data-lucide="refresh-cw" class="w-3.5 h-3.5 text-brand-accent"></i>
          <span class="hidden sm:inline text-[11px]">Refresh</span>
        </button>

        <span id="statusBar" class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-amber-500/10 text-amber-400 border border-amber-500/20">
          <span class="w-2 h-2 rounded-full bg-amber-400 animate-pulse"></span>
          <span id="statusLabel">Loading the best coins (60s)</span>
        </span>
      </div>
    </div>
  </header>

  <!-- Stale Session Recovery Toast -->
  <div id="staleToast" class="hidden fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
    <div class="bg-brand-surface border border-rose-500/40 rounded-2xl max-w-md w-full p-6 shadow-2xl space-y-4">
      <div class="flex items-center space-x-3 text-rose-400">
        <i data-lucide="alert-triangle" class="w-6 h-6"></i>
        <h3 class="text-base font-bold text-white">Session Stale (>5 Minutes Inactive)</h3>
      </div>
      <p class="text-xs text-slate-300 leading-relaxed">
        The market order flow and prediction weights have expired. Your latest prediction logs have been automatically exported as a verified CSV spreadsheet.
      </p>
      <div class="flex items-center justify-end space-x-3 pt-2">
        <button id="closeTabBtn" class="px-4 py-2 rounded-lg bg-brand-card hover:bg-slate-800 text-xs font-semibold mono text-slate-300 border border-brand-border transition">
          Close Window
        </button>
        <button id="reloadAppBtn" class="px-4 py-2 rounded-lg bg-brand-accent hover:bg-blue-600 text-xs font-semibold mono text-white transition flex items-center gap-1.5">
          <i data-lucide="refresh-cw" class="w-3.5 h-3.5"></i> Reload From Start
        </button>
      </div>
    </div>
  </div>

  <!-- Manual Refresh Confirmation Modal -->
  <div id="refreshConfirmModal" class="hidden fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
    <div class="bg-brand-surface border border-amber-500/40 rounded-2xl max-w-md w-full p-6 shadow-2xl space-y-4">
      <div class="flex items-center space-x-3 text-amber-400">
        <i data-lucide="help-circle" class="w-6 h-6"></i>
        <h3 class="text-base font-bold text-white">Are you sure you want to refresh?</h3>
      </div>
      <p class="text-xs text-slate-300 leading-relaxed">
        Refreshing will reset your active session timer and re-run the 60-second institutional screening pipeline. If you confirm, your current session audit CSV will be downloaded automatically before reloading.
      </p>
      <div class="flex items-center justify-end space-x-3 pt-2">
        <button id="cancelRefreshBtn" class="px-4 py-2 rounded-lg bg-brand-card hover:bg-slate-800 text-xs font-semibold mono text-slate-300 border border-brand-border transition">
          No, Stay Here
        </button>
        <button id="confirmRefreshBtn" class="px-4 py-2 rounded-lg bg-brand-accent hover:bg-blue-600 text-xs font-semibold mono text-white transition flex items-center gap-1.5">
          <i data-lucide="download" class="w-3.5 h-3.5"></i> Yes, Download Audit &amp; Refresh
        </button>
      </div>
    </div>
  </div>

  <!-- Dynamic Screener Progress Modal -->
  <div id="scannerBanner" class="bg-brand-card/90 border-b border-brand-border px-4 py-3 transition-all duration-300">
    <div class="max-w-7xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-3 text-xs mono">
      <div class="flex items-center gap-3">
        <div class="w-7 h-7 rounded-full bg-blue-500/20 text-brand-accent flex items-center justify-center font-bold" id="timerCount">
          60
        </div>
        <div>
          <span class="text-white font-semibold block" id="scannerStepTitle">Step 1: Institutional 4-Filter Screen</span>
          <span class="text-slate-400 text-[11px]" id="scannerStepSubtitle">Evaluating full 10-day institutional flow tape across active universe...</span>
        </div>
      </div>
      <div class="w-full sm:w-64 bg-brand-surface rounded-full h-2 border border-brand-border overflow-hidden">
        <div id="progressBar" class="bg-brand-accent h-full w-0 transition-all duration-500"></div>
      </div>
    </div>
  </div>

  <!-- Main Grid -->
  <main class="flex-1 max-w-7xl w-full mx-auto px-4 py-6 space-y-4">

    <!-- Top Workspace: Asset Selector, Live Spot, ML Ensemble Weights (Always Visible) -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
      <!-- Dynamic Screener Dropdown -->
      <div class="bg-brand-surface p-4 rounded-xl border border-brand-border flex flex-col justify-between">
        <div>
          <div class="flex items-center justify-between mb-2">
            <label class="block text-xs font-semibold text-slate-400 uppercase tracking-wider">Screened Universe</label>
            <div class="flex items-center gap-3">
              <label class="flex items-center gap-1.5 text-[10px] text-slate-300 font-medium cursor-pointer bg-brand-card px-2 py-0.5 rounded border border-brand-border hover:bg-slate-800 transition">
                <input type="checkbox" id="priceFilterToggle" class="accent-brand-accent cursor-pointer">
                &le; $10 Only
              </label>
              <span id="assetPoolCount" class="text-[10px] mono text-slate-500">Scanning...</span>
            </div>
          </div>
          <div class="relative">
            <select id="assetSelect" class="w-full bg-brand-card border border-brand-border text-white text-sm rounded-lg px-3 py-2.5 appearance-none focus:outline-none focus:border-brand-accent mono cursor-pointer" disabled>
              <option value="">Awaiting Filter...</option>
            </select>
            <i data-lucide="chevron-down" class="w-4 h-4 text-slate-400 absolute right-3 top-3 pointer-events-none"></i>
          </div>
        </div>
        <div class="mt-3 flex items-center justify-between text-xs text-slate-400">
          <span id="pipelineTag">Calmar &gt; 0 &amp; Sharpe &gt; 0</span>
          <span class="text-[11px] text-slate-500 mono">Session Locked</span>
        </div>
      </div>

      <!-- Live Spot Price -->
      <div class="bg-brand-surface p-4 rounded-xl border border-brand-border">
        <span class="text-xs font-semibold text-slate-400 uppercase tracking-wider">Spot Price</span>
        <div class="flex items-baseline gap-2 mt-1">
          <span id="spotPrice" class="text-2xl font-bold mono text-white">--</span>
          <span id="spotChange" class="text-xs font-semibold mono px-1.5 py-0.5 rounded">--</span>
        </div>
        <div class="grid grid-cols-2 gap-2 mt-3 pt-3 border-t border-brand-border/60 text-xs">
          <div>
            <span class="text-slate-400 block">High</span>
            <span id="high24h" class="mono font-medium text-slate-200">--</span>
          </div>
          <div>
            <span class="text-slate-400 block">Low</span>
            <span id="low24h" class="mono font-medium text-slate-200">--</span>
          </div>
        </div>
      </div>

      <!-- ML Ensemble Weights (Tri-Factor: Kinematic, Pain Path, Confluence Alpha) - Always Visible -->
      <div class="bg-brand-surface p-4 rounded-xl border border-brand-border">
        <div class="flex items-center justify-between">
          <span class="text-xs font-semibold text-slate-400 uppercase tracking-wider">ML Ensemble Weights</span>
          <span id="weightInitMode" class="text-[10px] mono text-slate-500">Baseline Init</span>
        </div>
        <div class="mt-2 space-y-1 text-xs">
          <div class="flex justify-between items-center">
            <span class="text-slate-400">W<sub>k</sub> (Kinematic):</span>
            <span id="modelWeightK" class="mono font-bold text-amber-400">0.300</span>
          </div>
          <div class="flex justify-between items-center">
            <span class="text-slate-400">W<sub>p</sub> (Pain Path):</span>
            <span id="modelWeightP" class="mono font-bold text-purple-400">0.350</span>
          </div>
          <div class="flex justify-between items-center">
            <span class="text-slate-400">W<sub>&alpha;</sub> (Alpha Confl.):</span>
            <span id="modelWeightAlpha" class="mono font-bold text-blue-400">0.350</span>
          </div>
          <div class="flex justify-between items-center pt-1 border-t border-brand-border/40">
            <span class="text-slate-400">Total Validations:</span>
            <span id="modelTotalSettled" class="mono font-bold text-slate-200">0</span>
          </div>
          <div class="flex justify-between items-center">
            <span class="text-slate-400">Cone Hit Rate (&plusmn;1&sigma;):</span>
            <span id="modelHitRate" class="mono font-bold text-emerald-400">--</span>
          </div>
        </div>
      </div>
    </div>

    <!-- ACCORDION 1: 1-Year Risk Adjusted Metrics -->
    <details class="bg-brand-surface rounded-xl border border-brand-border overflow-hidden group">
      <summary class="p-4 cursor-pointer flex items-center justify-between select-none hover:bg-brand-card/40 transition">
        <div class="flex items-center gap-2">
          <i data-lucide="award" class="w-4 h-4 text-emerald-400"></i>
          <span class="text-xs font-bold text-slate-200 uppercase tracking-wider">Risk Adjusted (1Y)</span>
        </div>
        <i data-lucide="chevron-down" class="w-4 h-4 text-slate-400 transition-transform duration-200 accordion-chevron"></i>
      </summary>
      <div class="p-4 pt-0 border-t border-brand-border/60">
        <div class="grid grid-cols-2 sm:grid-cols-4 gap-4 mt-3 text-xs">
          <div class="bg-brand-card p-3 rounded-lg border border-brand-border/60">
            <span class="text-slate-400 block">Calmar</span>
            <span id="statCalmar" class="font-bold mono text-emerald-400 text-base mt-0.5 block">--</span>
          </div>
          <div class="bg-brand-card p-3 rounded-lg border border-brand-border/60">
            <span class="text-slate-400 block">Sharpe (Rf=4%)</span>
            <span id="statSharpe" class="font-bold mono text-blue-400 text-base mt-0.5 block">--</span>
          </div>
          <div class="bg-brand-card p-3 rounded-lg border border-brand-border/60">
            <span class="text-slate-400 block">Max DD</span>
            <span id="statMDD" class="font-bold mono text-slate-200 text-base mt-0.5 block">--</span>
          </div>
          <div class="bg-brand-card p-3 rounded-lg border border-brand-border/60">
            <span class="text-slate-400 block">Ann. Vol</span>
            <span id="statVol" class="font-bold mono text-slate-200 text-base mt-0.5 block">--</span>
          </div>
        </div>
      </div>
    </details>

    <!-- ACCORDION 2: Technical & Microstructure Diagnostics -->
    <details class="bg-brand-surface rounded-xl border border-brand-border overflow-hidden group">
      <summary class="p-4 cursor-pointer flex items-center justify-between select-none hover:bg-brand-card/40 transition">
        <div class="flex items-center gap-2">
          <i data-lucide="cpu" class="w-4 h-4 text-brand-accent"></i>
          <span class="text-xs font-bold text-slate-200 uppercase tracking-wider">Technical &amp; Microstructure Diagnostics</span>
        </div>
        <i data-lucide="chevron-down" class="w-4 h-4 text-slate-400 transition-transform duration-200 accordion-chevron"></i>
      </summary>
      <div class="p-4 pt-0 border-t border-brand-border/60">
        <div class="grid grid-cols-1 md:grid-cols-4 gap-4 mt-3">
          <div class="bg-brand-card p-3.5 rounded-xl border border-brand-border">
            <span class="text-[11px] font-semibold text-slate-400 uppercase tracking-wider block">RSI Momentum</span>
            <span id="statRSI" class="text-lg font-bold mono text-white mt-1 block">--</span>
            <span class="text-[10px] text-slate-400 block mt-0.5">14-period</span>
          </div>
          <div class="bg-brand-card p-3.5 rounded-xl border border-brand-border">
            <span class="text-[11px] font-semibold text-slate-400 uppercase tracking-wider block">MACD Signal</span>
            <span id="statMACD" class="text-lg font-bold mono text-white mt-1 block">--</span>
            <span class="text-[10px] text-slate-400 block mt-0.5">9/21 cross</span>
          </div>
          <div class="bg-brand-card p-3.5 rounded-xl border border-brand-border">
            <span class="text-[11px] font-semibold text-slate-400 uppercase tracking-wider block">Markov Regime</span>
            <span id="statRegime" class="text-lg font-bold mono text-white mt-1 block">Evaluating...</span>
            <span id="statRegimeSub" class="text-[10px] text-slate-400 block mt-0.5">Adaptive 85th-pct Kurtosis</span>
          </div>
          <div class="bg-brand-card p-3.5 rounded-xl border border-brand-border">
            <span class="text-[11px] font-semibold text-slate-400 uppercase tracking-wider block">L2 Book Imbalance</span>
            <span id="statOFI" class="text-lg font-bold mono text-white mt-1 block">--</span>
            <span id="statDepthPressure" class="text-[10px] text-slate-400 block mt-0.5">Top-20 Depth</span>
          </div>
        </div>
      </div>
    </details>

    <!-- ACCORDION 3: 4 Institutional Quality Filters -->
    <details class="bg-brand-surface rounded-xl border border-brand-border overflow-hidden group">
      <summary class="p-4 cursor-pointer flex items-center justify-between select-none hover:bg-brand-card/40 transition">
        <div class="flex items-center gap-2">
          <span class="p-1 rounded bg-emerald-500/20 text-emerald-400 text-[10px] font-bold mono">VERIFIED FLOW</span>
          <span class="text-xs font-bold text-slate-200 uppercase tracking-wider">Institutional Quality Filters</span>
        </div>
        <i data-lucide="chevron-down" class="w-4 h-4 text-slate-400 transition-transform duration-200 accordion-chevron"></i>
      </summary>
      <div class="p-4 pt-0 border-t border-brand-border/60">
        <p class="text-xs text-slate-400 mt-3 mb-3">Strict gate: Passes if ALL 4 filters align today OR passed at least once over the last 10 days</p>
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div class="bg-brand-card p-3.5 rounded-lg border border-brand-border/80 flex flex-col justify-between">
            <div>
              <div class="flex items-center justify-between mb-1">
                <span class="flex items-center gap-2 text-xs font-bold text-slate-200">
                  <span class="w-5 h-5 rounded-full bg-emerald-500/20 text-emerald-400 flex items-center justify-center text-[10px]">1</span>
                  $5M+ Volume
                </span>
                <span id="filterStatus1" class="text-[10px] mono px-1.5 py-0.5 rounded bg-slate-800 text-slate-400">--</span>
              </div>
            </div>
            <div class="mt-3 pt-2 border-t border-brand-border/60 text-xs flex justify-between mono">
              <span class="text-slate-400">Volume:</span>
              <span id="filterVal1" class="font-bold text-white">--</span>
            </div>
          </div>

          <div class="bg-brand-card p-3.5 rounded-lg border border-brand-border/80 flex flex-col justify-between">
            <div>
              <div class="flex items-center justify-between mb-1">
                <span class="flex items-center gap-2 text-xs font-bold text-slate-200">
                  <span class="w-5 h-5 rounded-full bg-emerald-500/20 text-emerald-400 flex items-center justify-center text-[10px]">2</span>
                  Taker Buy &ge;50%
                </span>
                <span id="filterStatus2" class="text-[10px] mono px-1.5 py-0.5 rounded bg-slate-800 text-slate-400">--</span>
              </div>
            </div>
            <div class="mt-3 pt-2 border-t border-brand-border/60 text-xs flex justify-between mono">
              <span class="text-slate-400">Taker Buy:</span>
              <span id="filterVal2" class="font-bold text-white">--</span>
            </div>
          </div>

          <div class="bg-brand-card p-3.5 rounded-lg border border-brand-border/80 flex flex-col justify-between">
            <div>
              <div class="flex items-center justify-between mb-1">
                <span class="flex items-center gap-2 text-xs font-bold text-slate-200">
                  <span class="w-5 h-5 rounded-full bg-emerald-500/20 text-emerald-400 flex items-center justify-center text-[10px]">3</span>
                  Vol &gt; OI
                </span>
                <span id="filterStatus3" class="text-[10px] mono px-1.5 py-0.5 rounded bg-slate-800 text-slate-400">--</span>
              </div>
            </div>
            <div class="mt-3 pt-2 border-t border-brand-border/60 text-xs flex justify-between mono">
              <span class="text-slate-400">Vol/OI:</span>
              <span id="filterVal3" class="font-bold text-white">--</span>
            </div>
          </div>

          <div class="bg-brand-card p-3.5 rounded-lg border border-brand-border/80 flex flex-col justify-between">
            <div>
              <div class="flex items-center justify-between mb-1">
                <span class="flex items-center gap-2 text-xs font-bold text-slate-200">
                  <span class="w-5 h-5 rounded-full bg-emerald-500/20 text-emerald-400 flex items-center justify-center text-[10px]">4</span>
                  Range Disp &ge;40%
                </span>
                <span id="filterStatus4" class="text-[10px] mono px-1.5 py-0.5 rounded bg-slate-800 text-slate-400">--</span>
              </div>
            </div>
            <div class="mt-3 pt-2 border-t border-brand-border/60 text-xs flex justify-between mono">
              <span class="text-slate-400">Displacement:</span>
              <span id="filterVal4" class="font-bold text-white">--</span>
            </div>
          </div>
        </div>
      </div>
    </details>

    <!-- ACCORDION 4: Tri-Factor Ensemble Target Forecasts -->
    <details class="bg-brand-surface rounded-xl border border-brand-border overflow-hidden group" open>
      <summary class="p-4 cursor-pointer flex items-center justify-between select-none hover:bg-brand-card/40 transition">
        <div class="flex items-center gap-2">
          <i data-lucide="crosshair" class="w-4 h-4 text-brand-accent"></i>
          <span class="text-xs font-bold text-slate-200 uppercase tracking-wider">Tri-Factor Ensemble Target Forecasts</span>
          <span id="heatmapSourceIndicator" class="text-[10px] mono px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 font-bold ml-1">--</span>
        </div>
        <i data-lucide="chevron-down" class="w-4 h-4 text-slate-400 transition-transform duration-200 accordion-chevron"></i>
      </summary>
      <div class="p-4 pt-0 border-t border-brand-border/60">
        <div class="flex items-center justify-between mt-3 mb-3">
          <span class="text-xs text-slate-400 mono">ML Integration: W<sub>k</sub> (Kinematic) + W<sub>p</sub> (LOB Pain) + W<sub>&alpha;</sub> (FRVP 50-Bin / VWAP / UQ-LOB)</span>
        </div>
        <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3" id="predictionDeck"></div>
      </div>
    </details>

    <!-- ACCORDION 5: Forecast Trajectory & Confidence Envelope Chart -->
    <details class="bg-brand-surface rounded-xl border border-brand-border overflow-hidden group" open>
      <summary class="p-4 cursor-pointer flex items-center justify-between select-none hover:bg-brand-card/40 transition">
        <div class="flex items-center gap-2">
          <i data-lucide="trending-up" class="w-4 h-4 text-purple-400"></i>
          <span class="text-xs font-bold text-slate-200 uppercase tracking-wider">Forecast Trajectory &amp; Confidence Envelope</span>
        </div>
        <i data-lucide="chevron-down" class="w-4 h-4 text-slate-400 transition-transform duration-200 accordion-chevron"></i>
      </summary>
      <div class="p-4 pt-0 border-t border-brand-border/60">
        <div class="flex flex-col sm:flex-row sm:items-center justify-between py-3 gap-2">
          <p class="text-xs text-slate-400">Tri-factor mathematical diffusion, adaptive liquidity half-life traversal, and Auction Market POC alignment</p>
          <div class="flex items-center gap-2 mono text-xs">
            <span class="px-2 py-1 bg-brand-card rounded border border-brand-border text-slate-300">Auto-Track Active</span>
          </div>
        </div>
        <div class="relative h-72 sm:h-80 w-full">
          <canvas id="priceChart"></canvas>
        </div>
      </div>
    </details>

    <!-- Prediction Audit Ledger (Always Visible, Full History & Horizon Column Filter) -->
    <div class="bg-brand-surface rounded-xl border border-brand-border overflow-hidden">
      <div class="p-4 border-b border-brand-border flex flex-wrap items-center justify-between gap-2">
        <div class="flex items-center gap-2">
          <i data-lucide="clipboard-check" class="w-4 h-4 text-emerald-400"></i>
          <h3 class="font-bold text-white text-sm">Prediction Audit Ledger</h3>
        </div>
        <div class="flex items-center gap-3 text-xs mono">
          <span class="text-slate-400">Pending: <span id="pendingCount" class="text-amber-400 font-bold">0</span></span>
          <span class="text-slate-400">Settled: <span id="settledCount" class="text-emerald-400 font-bold">0</span></span>
          <button id="clearLedgerBtn" class="px-2 py-0.5 rounded bg-brand-card hover:bg-rose-500/20 hover:text-rose-400 border border-brand-border transition text-slate-400">
            Clear Ledger
          </button>
        </div>
      </div>
      <div class="overflow-x-auto max-h-[520px] overflow-y-auto">
        <table class="w-full text-left text-xs mono border-collapse">
          <thead class="sticky top-0 z-10 bg-brand-card">
            <tr class="text-slate-400 uppercase text-[11px] border-b border-brand-border">
              <th class="p-3">Asset</th>
              <th class="p-3">
                <div class="flex items-center gap-1.5">
                  <span>Horizon</span>
                  <select id="horizonFilter" class="bg-brand-surface border border-brand-border text-slate-200 text-[10px] rounded px-1.5 py-0.5 focus:outline-none focus:border-brand-accent cursor-pointer normal-case">
                    <option value="ALL">All</option>
                    <option value="1m">1m</option>
                    <option value="5m">5m</option>
                    <option value="15m">15m</option>
                    <option value="30m">30m</option>
                    <option value="1h">1h</option>
                  </select>
                </div>
              </th>
              <th class="p-3">Model Core</th>
              <th class="p-3">Target Time</th>
              <th class="p-3">Predicted</th>
              <th class="p-3">Realized</th>
              <th class="p-3">Deviation</th>
              <th class="p-3">Cone Status</th>
              <th class="p-3">Action / Timer</th>
            </tr>
          </thead>
          <tbody id="ledgerTableBody" class="divide-y divide-brand-border/60 text-slate-300">
            <tr>
              <td colspan="9" class="p-4 text-center text-slate-500">Initializing ledger...</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

  </main>

</body>
</html>
```
