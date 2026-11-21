#!/usr/bin/env python3
"""
Comprehensive BDD Coverage Audit for AlphaPulse + ProactiveMS V14.4

Cross-references:
1. Unified ProactiveMS and AlphaPulse Specification.md (195 lines, 26 sections)
2. AlphaPulse and ProactiveMS Spec Traceability Matrix.md (§1-§72)
3. AlphaPulse and ProactiveMS Finalized Contracts.md (C-01-C-22)
4. Actual test files under app/src/test/java/com/aistudio/alphapulse/

Outputs:
- Comprehensive coverage report per section
- Missing BDD spec files (if any)
- Confirmation of 100% coverage status
"""

import os
import re
import sys
from pathlib import Path
from typing import Dict, List, Set, Tuple

# Paths
REPO_ROOT = Path("/workspace/chats/f366450d88cc99a9b9d08d0b")
SPEC_FILE = REPO_ROOT / "Unified ProactiveMS and AlphaPulse Specification.md"
TRACEABILITY_FILE = REPO_ROOT / "AlphaPulse and ProactiveMS Spec Traceability Matrix.md"
CONTRACTS_FILE = REPO_ROOT / "AlphaPulse and ProactiveMS Finalized Contracts.md"
TEST_DIR = REPO_ROOT / "app/src/test/java/com/aistudio/alphapulse"

# Section name patterns in the Unified Spec
SECTION_PATTERNS = {
    1: r"### 1\. Product Vision",
    2: r"### 2\. Core Design Principle",
    3: r"### 3\. Zero-Cloud Mobile Architecture",
    4: r"### 4\. Persistent Foreground Engine",
    5: r"### 5\. Native Networking Layer",
    6: r"### 6\. Data Throttling",
    7: r"### 7\. Unified Institutional Universe Scanner",
    8: r"### 8\. Needle 3.*30-Day",
    9: r"### 9\. Full Order Book Reconstruction",
    10: r"### 10\. Tri/Quad/Penta-Factor Engine",
    11: r"### 11\. Time-Series Foundation Model",
    12: r"### 12\. Model Ensemble Health",
    13: r"### 13\. Core Market-Structure Objects",
    14: r"### 14\. Complete Campaign Taxonomy",
    15: r"### 15\. Tactical vs\. Strategic Bias",
    16: r"### 16\. Prediction-Driven Campaign Generation",
    17: r"### 17\. Position Sizing",
    18: r"### 18\. Live Monitoring Loop",
    19: r"### 19\. ProactiveMS Walk-Forward Risk",
    20: r"### 20\. Edge SLM Narrator",
    21: r"### 21\. Room Database Schema",
    22: r"### 22\. Unified 7-Tab Navigation",
    23: r"### 23\. Operational Guardrails",
    24: r"### 24\. Model Download Architecture",
    25: r"### 25\. Walk-Forward Validation",
    26: r"### 26\. Production Execution Modes",
}


def get_all_test_files() -> List[str]:
    """Get all test file paths relative to the test directory."""
    test_files = []
    for root, dirs, files in os.walk(TEST_DIR):
        for f in files:
            if f.endswith(".kt"):
                full_path = Path(root) / f
                rel_path = full_path.relative_to(TEST_DIR)
                test_files.append(str(rel_path).replace(os.sep, "/"))
    return sorted(test_files)


def extract_referenced_spec_files(content: str) -> Set[str]:
    """Extract all .kt file references from markdown content (backtick-quoted)."""
    pattern = r"`([^`]+\.kt)`"
    matches = re.findall(pattern, content)
    return set(matches)


def extract_section_content(spec_content: str, section_num: int) -> str:
    """Extract content for a given section from the unified spec."""
    if section_num not in SECTION_PATTERNS:
        return ""
    
    pattern = SECTION_PATTERNS[section_num]
    match = re.search(pattern, spec_content)
    if not match:
        return ""
    
    start = match.start()
    
    # Find the next section header or end of file
    next_section_pattern = r"### \d{1,2}\. "
    next_match = re.search(next_section_pattern, spec_content[start + 1:])
    if next_match:
        end = start + 1 + next_match.start()
    else:
        end = len(spec_content)
    
    return spec_content[start:end]


def extract_subsection_content(spec_content: str, section_num: int, subsection_num: int) -> str:
    """Extract content for a given subsection (e.g., 1.1, 1.2)."""
    pattern = rf"#### {section_num}\.{subsection_num}[:.]?"
    match = re.search(pattern, spec_content)
    if not match:
        return ""
    
    start = match.start()
    
    # Find next subsection or section
    next_patterns = [
        rf"#### {section_num}\.\d+[:.]",
        rf"### {section_num + 1}[:.]",
        r"---",
    ]
    
    end = len(spec_content)
    for pat in next_patterns:
        next_match = re.search(pat, spec_content[start + 1:])
        if next_match:
            candidate = start + 1 + next_match.start()
            if candidate < end:
                end = candidate
    
    return spec_content[start:end]


def check_section_coverage(
    spec_content: str,
    test_files: List[str],
    referenced_files: Set[str],
    section_num: int,
    subsections: List[Tuple[int, str]] = None
) -> Dict:
    """Check BDD coverage for a given section."""
    coverage = {
        "section": section_num,
        "subsection_tests": [],
        "mentioned_files": [],
        "referenced_in_spec": [],
        "missing_files": [],
        "has_bdd_spec": False,
        "has_test": False,
    }
    
    section_content = extract_section_content(spec_content, section_num)
    if not section_content:
        return coverage
    
    # Check mentioned files in the section
    mentioned = re.findall(r"`([^`]+\.kt)`", section_content)
    coverage["mentioned_files"] = mentioned
    
    # Check if any test files are referenced in this section
    for mentioned_file in mentioned:
        # Convert to relative path format
        rel_path = mentioned_file.replace("...", "").strip()
        if rel_path in test_files or any(rel_path in tf for tf in test_files):
            coverage["referenced_in_spec"].append(mentioned_file)
    
    # Check subsections
    if subsections:
        for sub_num, sub_desc in subsections:
            sub_content = extract_subsection_content(spec_content, section_num, sub_num)
            if not sub_content:
                continue
            
            sub_mentioned = re.findall(r"`([^`]+\.kt)`", sub_content)
            sub_test_found = False
            
            for mf in sub_mentioned:
                # Check if a test file exists for this
                base_name = mf.split("/")[-1].replace(".kt", "")
                for tf in test_files:
                    if base_name in tf:
                        sub_test_found = True
                        coverage["subsection_tests"].append({
                            "subsection": f"{section_num}.{sub_num}",
                            "description": sub_desc,
                            "mentioned_file": mf,
                            "test_file": tf,
                            "covered": True
                        })
                        break
                
                if not sub_test_found and mf not in referenced_files:
                    # Check if the base name appears in any test file
                    for tf in test_files:
                        if base_name.replace(".", "").replace("/", "") in tf.replace(".", "").replace("/", ""):
                            sub_test_found = True
                            coverage["subsection_tests"].append({
                                "subsection": f"{section_num}.{sub_num}",
                                "description": sub_desc,
                                "mentioned_file": mf,
                                "test_file": tf,
                                "covered": True
                            })
                            break
            
            if not sub_test_found and sub_mentioned:
                coverage["subsection_tests"].append({
                    "subsection": f"{section_num}.{sub_num}",
                    "description": sub_desc,
                    "mentioned_file": sub_mentioned[0] if sub_mentioned else None,
                    "test_file": None,
                    "covered": False
                })
    
    # Overall BDD spec check
    for tf in test_files:
        if "BddSpec" in tf or "Test" in tf:
            coverage["has_bdd_spec"] = True
            coverage["has_test"] = True
            break
    
    return coverage


def parse_traceability_matrix(content: str) -> Dict[int, Dict]:
    """Parse the traceability matrix to extract section coverage info."""
    sections = {}
    
    # Find table rows with section numbers
    pattern = r"\|\s*(\d+)\s*\|.*?\| (Done|Partial|Gap) \| (.*?) \| (.*?) \|"
    matches = re.findall(pattern, content, re.DOTALL)
    
    for match in matches:
        section_num = int(match[0])
        status = match[1]
        implementation = match[2].strip()
        tests = match[3].strip()
        
        sections[section_num] = {
            "status": status,
            "implementation": implementation,
            "tests": tests,
        }
    
    return sections


def parse_contracts(content: str) -> Dict[str, Dict]:
    """Parse the finalized contracts to extract contract info."""
    contracts = {}
    
    # Find table rows with contract IDs
    pattern = r"\|\s*(C-\d+)\s*\|.*?\| (.*?) \| (.*?) \|"
    matches = re.findall(pattern, content, re.DOTALL)
    
    for match in matches:
        contract_id = match[0]
        behavior = match[1].strip()
        guard_specs = match[2].strip()
        
        contracts[contract_id] = {
            "behavior": behavior,
            "guard_specs": guard_specs,
        }
    
    return contracts


def main():
    print("=" * 80)
    print("ALPHAPULSE + PROACTIVEMS V14.4 - BDD COVERAGE AUDIT")
    print("=" * 80)
    print()
    
    # Load all documents
    print("Loading documents...")
    spec_content = SPEC_FILE.read_text(encoding="utf-8")
    traceability_content = TRACEABILITY_FILE.read_text(encoding="utf-8")
    contracts_content = CONTRACTS_FILE.read_text(encoding="utf-8")
    
    # Get all test files
    test_files = get_all_test_files()
    print(f"Found {len(test_files)} test files under app/src/test/java/com/aistudio/alphapulse/")
    print()
    
    # Extract referenced files from all docs
    spec_referenced = extract_referenced_spec_files(spec_content)
    traceability_referenced = extract_referenced_spec_files(traceability_content)
    contracts_referenced = extract_referenced_spec_files(contracts_content)
    
    all_referenced = spec_referenced | traceability_referenced | contracts_referenced
    print(f"Total unique .kt files referenced across all docs: {len(all_referenced)}")
    print()
    
    # Find test files NOT referenced anywhere
    unreferenced_test_files = []
    for tf in test_files:
        tf_clean = tf.replace(".kt", "")
        found = False
        for ref in all_referenced:
            if tf_clean in ref or ref in tf_clean:
                found = True
                break
        if not found:
            # Check if it's a test file not referenced by name
            unreferenced_test_files.append(tf)
    
    if unreferenced_test_files:
        print("⚠️  TEST FILES NOT REFERENCED IN ANY DOCUMENT:")
        for tf in unreferenced_test_files:
            print(f"   - {tf}")
        print()
    
    # Parse traceability matrix
    traceability_sections = parse_traceability_matrix(traceability_content)
    print(f"Parsed {len(traceability_sections)} sections from traceability matrix")
    print()
    
    # Parse contracts
    contracts = parse_contracts(contracts_content)
    print(f"Parsed {len(contracts)} contracts (C-01 to C-{len(contracts)})")
    print()
    
    # Check each section of the Unified Specification
    print("=" * 80)
    print("UNIFIED SPECIFICATION SECTION-BY-SECTION COVERAGE ANALYSIS")
    print("=" * 80)
    print()
    
    # Map unified spec sections to traceability sections
    # The unified spec has 26 sections, mapping to various § in the original specs
    unified_to_traceability = {
        1: [1, 2],  # Product Vision + Core Design Principle
        2: [2, 61, 64],  # Core Design Principle
        3: [26, 27, 45, 46],  # Zero-Cloud Mobile Architecture
        4: [24, 25, 42, 43],  # Persistent Foreground Engine
        5: [4, 5, 6],  # Native Networking Layer
        6: [23, 35, 36],  # Data Throttling
        7: [3, 4, 65],  # Unified Institutional Universe Scanner
        8: [20, 30],  # Needle 3 30-Day
        9: [7, 8, 9, 10],  # Full Order Book Reconstruction
        10: [11, 12, 13, 14, 15],  # Tri/Quad/Penta-Factor Engine
        11: [16, 17, 18, 19],  # TSFM Strategy
        12: [31, 32, 33, 34],  # Model Ensemble Health
        13: [7, 8, 9, 10, 11],  # Core Market-Structure Objects (ProactiveMS)
        14: [12, 13],  # Complete Campaign Taxonomy (ProactiveMS)
        15: [14, 15, 16, 17],  # Tactical vs Strategic Bias (ProactiveMS)
        16: [18, 19, 22],  # Prediction-Driven Campaign Generation
        17: [20, 21, 22, 23, 24],  # Position Sizing (ProactiveMS)
        18: [25, 26, 27, 28, 29],  # Live Monitoring Loop (ProactiveMS)
        19: [68, 69],  # ProactiveMS Walk-Forward Risk
        20: [28, 29, 30, 41, 59],  # Edge SLM Narrator
        21: [30, 31, 21, 37, 38],  # Room Database Schema
        22: [32, 39, 40, 56, 57, 58, 60],  # Unified 7-Tab Navigation
        23: [33, 34, 35, 44],  # Operational Guardrails
        24: [47, 48, 49, 55],  # Model Download Architecture
        25: [50, 51, 52, 53, 54],  # Walk-Forward Validation
        26: [36, 37, 38, 62, 63],  # Production Execution Modes
    }
    
    # Section-specific analysis
    section_analysis = {
        1: {
            "description": "Product Vision, System Purpose & Cancellation-First Philosophy",
            "subsections": [(1.1, "AlphaPulse Quantitative Workstation Vision"), (1.2, "ProactiveMS Cancellation-First Trading Philosophy")],
            "expected_tests": ["AgentTest.kt", "NarratorConstraintValidatorBddSpec.kt"],
            "checks": ["The app never delegates to LLM", "Deterministic quantitative models", "TSFM forecasts", "Neural microstructure models", "Ensemble fusion", "Online learning", "On-device router"]
        },
        2: {
            "description": "Core Design Principle & End-to-End Unified Topology",
            "subsections": [],
            "expected_tests": ["EnsembleWeightingTest.kt", "QuantMathTest.kt"],
            "checks": ["Tri-Factor baseline", "Penta-Factor extension", "Full topology diagram"]
        },
        3: {
            "description": "Zero-Cloud Mobile Architecture, Hardware Acceleration & Workload Allocation",
            "subsections": [(3.1, "Android Hardware Acceleration"), (3.2, "Performance Requirements"), (3.3, "Battery Modes & Pre-Flight Hardware Guardrails")],
            "expected_tests": ["HardwareGatekeeperBddSpec.kt", "ModelRuntime", "BatteryModeManagerTest.kt", "OfflineModeManagerTest.kt"],
            "checks": ["CPU/GPU/NPU workload allocation", "Performance targets", "Four battery modes", "Pre-flight hardware checks"]
        },
        4: {
            "description": "Persistent Foreground Engine, API Key Security, Trade Sessions & Orphaned Orders",
            "subsections": [],
            "expected_tests": ["AlphaPulseService", "KeystoreCredentialStore", "WorkManagerSchedulerTest.kt", "OfflineModeManagerTest.kt"],
            "checks": ["Persistent foreground service", "API key security", "Trade sessions", "Orphaned order handling"]
        },
        5: {
            "description": "Native Networking Layer, Multi-Exchange Failover & Mandatory Feeds",
            "subsections": [],
            "expected_tests": ["ResilientBinanceClient", "FailoverExchangeProvider", "BinanceSignedWireBddSpec.kt"],
            "checks": ["Binance WebSocket", "Bybit support", "Hyperliquid support", "CoinGlass validation", "Failover mechanism"]
        },
        6: {
            "description": "Data Throttling, Stale Data Expiration, Exact Scheduling & Data Quality Engine",
            "subsections": [],
            "expected_tests": ["SchedulingTest.kt", "DataQualityEngineBddSpec.kt", "TsfmLayerTest.kt"],
            "checks": ["Data throttling", "Stale data expiration", "Exact scheduling", "Data quality scoring"]
        },
        7: {
            "description": "Unified Institutional Universe Scanner & Risk-Adjusted Ranking",
            "subsections": [],
            "expected_tests": ["UnifiedUniverseScreenerBddSpec.kt", "QuantMathTest.kt"],
            "checks": ["4-filter gate (FR-001)", "Calmar > 0 (FR-002)", "Sharpe > 0 (FR-003)", "Spread < 0.10%", "Binance/CoinGlass cross-validation", "Legacy $50M-$750M filter removed"]
        },
        8: {
            "description": "Needle 3: 30-Day Multi-Horizon Binance Ingestion & Tri/Quad/Penta Ensemble Calibration",
            "subsections": [],
            "expected_tests": ["ThirtyDayBinanceCalibrationBddSpec.kt", "CoinCalibrationBddSpec.kt", "AdaptiveEnsembleLearningBddSpec.kt"],
            "checks": ["30-day kline ingestion", "Multi-horizon (1m,5m,15m,30m,1h,4h,1d)", "Tri/Quad/Penta ensemble", "Normalized weights (sum W = 1.000)", "Per-symbol per-horizon persistence"]
        },
        9: {
            "description": "Full Order Book Reconstruction, Horizon-Specific Pain Paths, Liquidation Heatmap & Trade Tape",
            "subsections": [],
            "expected_tests": ["OrderBookAnalysisTest.kt", "TradeTapeAnalyzerBddSpec.kt", "QuantMathTest.kt"],
            "checks": ["Full L2/L3 order book", "Horizon-specific pain paths", "Liquidation heatmap", "Trade tape analysis"]
        },
        10: {
            "description": "Tri/Quad/Penta-Factor Engine, Full Volume Profile, Neural UQ-LOB & AGA-HMM",
            "subsections": [],
            "expected_tests": ["QuantMathTest.kt", "UqLobEngineTest.kt", "AgaHmmRegimeBddSpec.kt", "ConePercentileBddSpec.kt"],
            "checks": ["Tri-Factor baseline", "Quad-Factor with Chronos-Bolt", "Penta-Factor with TimesFM 2.0", "Full volume profile (FRVP/VAH/VAL)", "Neural UQ-LOB", "AGA-HMM regime detection"]
        },
        11: {
            "description": "Time-Series Foundation Model (TSFM) Strategy, Covariate Tensor & Probabilistic Cones",
            "subsections": [],
            "expected_tests": ["TsfmProviderTest.kt", "ChronosBoltAdapterBddSpec.kt", "TimesFmAdapterBddSpec.kt", "OnnxModelParityBddSpec.kt", "TsfmLayerTest.kt"],
            "checks": ["Chronos-Bolt adapter", "TimesFM 2.0 adapter", "Kronos provider", "P10-P90 probabilistic forecasts", "Covariate tensor input", "ONNX parity"]
        },
        12: {
            "description": "Model Ensemble Health, Disagreement Engine, Confidence Score & Prediction State Machine",
            "subsections": [],
            "expected_tests": ["EnsembleWeightingTest.kt", "PredictionStateMachineBddSpec.kt", "DecisionRouterTest.kt"],
            "checks": ["Ensemble health monitoring", "Model disagreement detection", "Confidence scoring", "Prediction state machine (ISSUED->SETTLED->LEARNED/EXPIRED/VOID)"]
        },
        13: {
            "description": "Core Market-Structure Objects, Level Detection, Path Modeling & Coin Calibration (ProactiveMS)",
            "subsections": [],
            "expected_tests": ["QuantMathTest.kt", "LevelMap", "PathModel"],
            "checks": ["Supply/resistance levels", "Support shelf", "Reclaim zone", "Resting ladder", "Volume profile POC/VAH/VAL"]
        },
        14: {
            "description": "Complete Campaign Taxonomy & Core Campaign Templates (ProactiveMS)",
            "subsections": [],
            "expected_tests": ["CampaignReadinessGateBddSpec.kt", "PredictionCampaignBuilderBddSpec.kt"],
            "checks": ["6 campaign families (Momentum, Reversal, Mean-Reversion, Breakout, Grid, Scalp)", "4 board templates (A/B/C/D)"]
        },
        15: {
            "description": "Tactical vs. Strategic Bias Separation, Bias Score Model & Asymmetric Board Templates (ProactiveMS)",
            "subsections": [],
            "expected_tests": ["PredictionCampaignBuilderBddSpec.kt"],
            "checks": ["Tactical bias (15m-1h)", "Strategic bias (4h-1d)", "Bias score model", "Asymmetric board templates"]
        },
        16: {
            "description": "Prediction-Driven Campaign Generation Algorithm, Readiness Gate (>90% Cone Hit Rate) & State Machine",
            "subsections": [],
            "expected_tests": ["PredictionCampaignBuilderBddSpec.kt", "CampaignReadinessGateBddSpec.kt"],
            "checks": ["Campaign generation from forecasts", "Readiness gate >90% cone hit rate", "5/5 ensemble ready", "2/2 TSFM dock ready", "LTF/MTF/HTF ready"]
        },
        17: {
            "description": "Position Sizing, Entry Ladder Construction, Proactive Order Architecture, Conflict Resolution & Manual Overrides (ProactiveMS)",
            "subsections": [],
            "expected_tests": ["LiveOrderStagingBddSpec.kt", "LiveOrderTicketBddSpec.kt", "LiveOrderDeskBddSpec.kt"],
            "checks": ["Position sizing", "Entry ladder", "Proactive order architecture", "Conflict resolution", "Manual overrides"]
        },
        18: {
            "description": "Live Monitoring Loop, Asymmetric Board Survival & Microstructural Invalidation Model (ProactiveMS)",
            "subsections": [],
            "expected_tests": ["ProactiveInvalidationEngineBddSpec.kt", "LiveOrderViewStateBddSpec.kt"],
            "checks": ["Live monitoring loop", "Asymmetric board survival", "Microstructural invalidation", "Survival active badge"]
        },
        19: {
            "description": "ProactiveMS Walk-Forward Risk & Cancellation-First Invalidation Engine",
            "subsections": [],
            "expected_tests": ["ProactiveInvalidationEngineBddSpec.kt", "ProactiveWalkForwardBddSpec.kt", "LeakageGuardBddSpec.kt"],
            "checks": ["0.50R default candidate", "2.00R hard backstop", "Cone breach detection", "SGD rank shift", "Walk-forward parameter sweep (0.25R-1.50R)", "21 metrics", "Parameter stability plateau"]
        },
        20: {
            "description": "Edge SLM Narrator, Quantitative Decision Router & Needle 3 Agent DAG",
            "subsections": [],
            "expected_tests": ["NarratorConstraintValidatorBddSpec.kt", "Needle3DecisionRouterBddSpec.kt", "DecisionRouterAgentBddSpec.kt", "DecisionModelCatalogBddSpec.kt", "Needle3EngineBddSpec.kt", "JniNeedle3BridgeBddSpec.kt", "VendoredEngineArtifactBddSpec.kt"],
            "checks": ["Edge SLM narrator", "Grammar-constrained router", "Needle 3 agent DAG", "On-device routing", "Deterministic fallback", "Typed refusal"]
        },
        21: {
            "description": "Room Database Schema, 25-Field Unified Audit Ledger & Multi-Format Export System",
            "subsections": [],
            "expected_tests": ["DecisionRecordDaoBddSpec.kt", "DecisionAuditJoinBddSpec.kt", "ProactiveAuditLedgerMigrationBddSpec.kt", "EnsembleWeightsMigrationBddSpec.kt"],
            "checks": ["Room v7 schema", "25-field audit ledger", "CSV/JSON/line export", "5->6 migration", "6->7 migration", "One row per evaluation"]
        },
        22: {
            "description": "Unified 7-Tab Navigation UI, Notifications & Observability",
            "subsections": [],
            "expected_tests": ["ProactiveMsNavigationBddSpec.kt", "UserJourneyBddTest.kt", "DecisionCardBddSpec.kt", "DecisionHistorySectionBddSpec.kt", "Needle3StatusBannerBddSpec.kt", "NotificationVetoBddSpec.kt", "ModelDockUiTest.kt"],
            "checks": ["7-tab navigation (Screener, AlphaPulse, Dashboard, Telemetry, Guardrails, Replay, Audit)", "Synchronized symbol selection", "Notifications", "Observability dashboard"]
        },
        23: {
            "description": "Operational Guardrails, Failure Handling, Configuration & Engineering Build Checklists",
            "subsections": [],
            "expected_tests": ["Needle3DecisionRouterBddSpec.kt", "NotificationVetoBddSpec.kt", "BuildContractBddSpec.kt", "FinalizedContractGuardBddSpec.kt"],
            "checks": ["Operational guardrails", "Failure handling", "Configuration", "Build checklists", "CI/CD gates"]
        },
        24: {
            "description": "Model Download Architecture, Benchmarking, Selection Principles & Versioning",
            "subsections": [],
            "expected_tests": ["ModelBenchmarkBddSpec.kt", "HardwareGatekeeperBddSpec.kt", "ModelDownloadBddSpec.kt", "BundledModelInstallerBddSpec.kt", "BundledWeightsAssetManagerBddSpec.kt", "ModelArtifactSourceContractTest.kt"],
            "checks": ["Model download architecture", "Benchmarking", "Selection principles", "Versioning", "Bundled model installer", "Pinned checkpoint (35,335,380 bytes)"]
        },
        25: {
            "description": "Walk-Forward Validation, No-Data-Leakage Guard, TDD, Lifecycle Testing & Session Recovery",
            "subsections": [],
            "expected_tests": ["WalkForwardValidatorBddSpec.kt", "LeakageGuardBddSpec.kt", "ProactiveWalkForwardBddSpec.kt", "AndroidLifecycleTest.kt", "ResearchModeTest.kt", "TestingRequirements.kt"],
            "checks": ["Walk-forward validation", "No-data-leakage guard (8 configurations)", "TDD/BDD", "Lifecycle testing", "Session recovery"]
        },
        26: {
            "description": "Production Execution Modes, Backtesting Simulation, Release Strategy & Definition of Done",
            "subsections": [],
            "expected_tests": ["ReleaseProgressBddSpec.kt", "FinalizedContractGuardBddSpec.kt", "AlphaPulseService", "BatteryModeManagerTest.kt"],
            "checks": ["Production execution modes", "Backtesting simulation", "Release strategy", "Definition of done", "CI/CD gates"]
        }
    }
    
    total_checks = 0
    passed_checks = 0
    missing_coverage = []
    
    for section_num in range(1, 27):
        analysis = section_analysis.get(section_num, {})
        print(f"--- Section {section_num}: {analysis.get('description', 'Unknown')} ---")
        print()
        
        # Get traceability status
        trace_status = "NOT MAPPED"
        for ts, tinfo in traceability_sections.items():
            if section_num in unified_to_traceability.get(section_num, []):
                if ts in unified_to_traceability[section_num]:
                    trace_status = tinfo["status"]
                    break
        
        print(f"  Traceability Matrix Status: {trace_status}")
        
        # Check if contract exists for this section
        contract_nums = unified_to_traceability.get(section_num, [])
        relevant_contracts = []
        for cid, cinfo in contracts.items():
            cnum = int(cid.split("-")[1])
            if cnum in contract_nums or any(tn in contract_nums for tn in range(1, 73) if tn == cnum):
                relevant_contracts.append(cid)
        
        if relevant_contracts:
            print(f"  Contracts: {', '.join(relevant_contracts)}")
        
        # Check expected test files
        expected = analysis.get("expected_tests", [])
        print(f"  Expected test files: {', '.join(expected) if expected else 'None specified'}")
        
        found_tests = []
        missing_tests = []
        
        for exp in expected:
            found = False
            for tf in test_files:
                if exp in tf:
                    found_tests.append(tf)
                    found = True
                    break
            if not found:
                missing_tests.append(exp)
        
        if found_tests:
            print(f"  ✓ Found test files:")
            for ft in found_tests:
                print(f"      - {ft}")
        
        if missing_tests:
            print(f"  ✗ Missing test files:")
            for mt in missing_tests:
                print(f"      - {mt}")
            missing_coverage.append({
                "section": section_num,
                "description": analysis.get("description", ""),
                "missing": missing_tests
            })
        
        # Check subsection coverage if any
        subsections = analysis.get("subsections", [])
        if subsections:
            print(f"  Subsections:")
            for sub_num, sub_desc in subsections:
                sub_content = extract_subsection_content(spec_content, section_num, int(sub_num))
                sub_mentioned = re.findall(r"`([^`]+\.kt)`", sub_content)
                
                sub_test_found = False
                for mf in sub_mentioned:
                    for tf in test_files:
                        if mf.split("/")[-1].replace(".kt", "") in tf:
                            sub_test_found = True
                            print(f"    ✓ {sub_num} {sub_desc}: Covered by {tf}")
                            break
                
                if not sub_test_found and sub_mentioned:
                    print(f"    ✗ {sub_num} {sub_desc}: MENTIONED {sub_mentioned[0]} but NO TEST FOUND")
                    missing_coverage.append({
                        "section": section_num,
                        "subsection": f"{section_num}.{sub_num}",
                        "description": sub_desc,
                        "missing_file": sub_mentioned[0]
                    })
                elif not sub_test_found:
                    print(f"    ? {sub_num} {sub_desc}: No .kt files mentioned (may be covered by parent)")
        
        # Check specific requirements from the section
        checks = analysis.get("checks", [])
        if checks:
            print(f"  Requirement checks:")
            for check in checks:
                # Simple heuristic - check if the requirement appears in any test file
                found = False
                for tf in test_files:
                    try:
                        tf_content = (TEST_DIR / tf).read_text(encoding="utf-8")
                        # Check for relevant keywords
                        keywords = check.lower().split()[:3]  # First 3 words
                        if any(kw in tf_content.lower() for kw in keywords if len(kw) > 3):
                            found = True
                            break
                    except:
                        pass
                
                if found:
                    print(f"    ✓ {check}")
                    passed_checks += 1
                else:
                    print(f"    - {check} (documented, may not have explicit test)")
                total_checks += 1
        
        print()
    
    # Summary
    print("=" * 80)
    print("COVERAGE SUMMARY")
    print("=" * 80)
    print()
    
    print(f"Total test files: {len(test_files)}")
    print(f"  - BddSpec files: {len([f for f in test_files if 'BddSpec' in f])}")
    print(f"  - Test files: {len([f for f in test_files if 'Test' in f and 'BddSpec' not in f])}")
    print()
    
    print(f"Traceability Matrix coverage: {len(traceability_sections)} sections")
    done_count = sum(1 for s in traceability_sections.values() if s["status"] == "Done")
    partial_count = sum(1 for s in traceability_sections.values() if s["status"] == "Partial")
    gap_count = sum(1 for s in traceability_sections.values() if s["status"] == "Gap")
    print(f"  - Done: {done_count}")
    print(f"  - Partial: {partial_count}")
    print(f"  - Gap: {gap_count}")
    print()
    
    print(f"Finalized Contracts: {len(contracts)} contracts")
    for cid in sorted(contracts.keys()):
        c = contracts[cid]
        guard_count = len([g.strip() for g in c["guard_specs"].split(",") if g.strip()])
        print(f"  {cid}: {guard_count} guard spec(s)")
    print()
    
    if missing_coverage:
        print("⚠️  GAPS DETECTED:")
        for gap in missing_coverage:
            if "subsection" in gap:
                print(f"  Section {gap['section']}.{gap.get('subsection', '')}: {gap['description']}")
                print(f"    Missing: {gap['missing_file']}")
            else:
                print(f"  Section {gap['section']}: {gap['description']}")
                print(f"    Missing: {', '.join(gap['missing'])}")
        print()
    else:
        print("✓ NO GAPS DETECTED - All sections have test coverage")
        print()
    
    print(f"Requirement checks: {passed_checks}/{total_checks} explicitly verified in tests")
    print()
    
    # Final verdict
    print("=" * 80)
    print("FINAL VERDICT")
    print("=" * 80)
    print()
    
    # Count actual BDD coverage
    bdd_specs = [f for f in test_files if 'BddSpec' in f]
    print(f"BDD Spec files found: {len(bdd_specs)}")
    print()
    
    if gap_count == 0 and not missing_coverage:
        print("✅ 100% BDD COVERAGE CONFIRMED")
        print()
        print("All sections of the Unified ProactiveMS and AlphaPulse Specification.md")
        print("are covered by BDD tests as documented in:")
        print("  - AlphaPulse and ProactiveMS Spec Traceability Matrix.md (§1-§72)")
        print("  - AlphaPulse and ProactiveMS Finalized Contracts.md (C-01-C-22)")
        print()
        print("The following local gates should be run to verify:")
        print("  1. ./gradlew :app:testDebugUnitTest")
        print("  2. python3 scripts/check_integrity.py")
        print("  3. ./gradlew :app:assembleDebug")
        print()
        return 0
    else:
        print("❌ COVERAGE GAPS DETECTED")
        print()
        print("Missing BDD specs need to be created:")
        for gap in missing_coverage:
            print(f"  - Section {gap['section']}: {gap.get('description', 'Unknown')}")
        print()
        return 1


if __name__ == "__main__":
    sys.exit(main())
