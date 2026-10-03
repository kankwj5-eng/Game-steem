#!/usr/bin/env python3
import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json"

RECOMMENDATIONS = {
    "semgrep": "Revisar cada flujo marcado, corregir la causa raíz (validación, autorización, sanitización o uso inseguro de APIs) y añadir una prueba de regresión.",
    "gitleaks": "Si el hallazgo es real, revocar o rotar el secreto inmediatamente, retirarlo del código y del historial cuando corresponda, y usar secretos de GitHub o configuración externa.",
    "trivy": "Actualizar la dependencia afectada a una versión corregida, revisar cambios incompatibles y volver a ejecutar Trivy hasta eliminar vulnerabilidades HIGH/CRITICAL.",
    "detekt": "Corregir los errores de Kotlin que indiquen comportamiento riesgoso o mala gestión de concurrencia/recursos y cubrirlos con pruebas.",
    "cppcheck": "Priorizar errores de memoria, límites, uso tras liberar, null dereference, concurrencia y comportamiento indefinido; añadir pruebas o sanitizers cuando aplique.",
    "ruff": "Corregir los problemas Python reportados para mantener scripts de CI y tooling predecibles. Los hallazgos Ruff no se consideran graves por sí solos.",
}

def empty_sarif(tool: str):
    return {
        "version": "2.1.0",
        "$schema": SARIF_SCHEMA,
        "runs": [{
            "tool": {"driver": {"name": tool, "rules": []}},
            "results": []
        }]
    }

def write_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

def severity_is_high(value):
    if value is None:
        return False
    text = str(value).strip().upper()
    if text in {"HIGH", "CRITICAL", "ERROR", "FATAL", "BLOCKER"}:
        return True
    try:
        return float(text) >= 7.0
    except ValueError:
        return False

def first_location(result):
    try:
        loc = result.get("locations", [])[0].get("physicalLocation", {})
        uri = loc.get("artifactLocation", {}).get("uri", "")
        region = loc.get("region", {})
        line = region.get("startLine")
        col = region.get("startColumn")
        if not uri:
            return ""
        suffix = f":{line}" if line else ""
        if col:
            suffix += f":{col}"
        return uri + suffix
    except Exception:
        return ""

def summarize_sarif(tool, input_path: Path):
    if not input_path.is_file() or input_path.stat().st_size == 0:
        data = empty_sarif(tool)
        return data, [], False
    try:
        data = json.loads(input_path.read_text(encoding="utf-8"))
    except Exception:
        data = empty_sarif(tool)
        return data, [], False

    findings = []
    severe = False
    for run in data.get("runs", []):
        rule_props = {}
        for rule in run.get("tool", {}).get("driver", {}).get("rules", []) or []:
            rule_props[rule.get("id", "")] = rule.get("properties", {}) or {}
        for result in run.get("results", []) or []:
            rid = result.get("ruleId") or "sin-regla"
            msg = result.get("message", {}).get("text") or result.get("message", {}).get("markdown") or ""
            level = str(result.get("level") or "").lower()
            props = {}
            props.update(rule_props.get(rid, {}))
            props.update(result.get("properties", {}) or {})
            high = level == "error" or severity_is_high(props.get("severity")) or severity_is_high(props.get("security-severity"))
            if tool == "gitleaks":
                high = True
            severe = severe or high
            findings.append({
                "rule": rid,
                "message": msg.strip(),
                "location": first_location(result),
                "severity": (props.get("severity") or props.get("security-severity") or level or "n/d"),
                "severe": high,
            })
    return data, findings, severe

def sarif_from_findings(tool, findings):
    rules = {}
    results = []
    for f in findings:
        rid = f.get("rule") or "finding"
        rules.setdefault(rid, {
            "id": rid,
            "name": rid,
            "shortDescription": {"text": rid},
        })
        loc = f.get("location") or ""
        uri, line, col = loc, None, None
        if ":" in loc:
            parts = loc.rsplit(":", 2)
            if len(parts) >= 2 and parts[-1].isdigit():
                if len(parts) == 3 and parts[-2].isdigit():
                    uri, line, col = parts[0], int(parts[-2]), int(parts[-1])
                else:
                    uri, line = ":".join(parts[:-1]), int(parts[-1])
        physical = {"artifactLocation": {"uri": uri or "unknown"}}
        if line:
            physical["region"] = {"startLine": line}
            if col:
                physical["region"]["startColumn"] = col
        results.append({
            "ruleId": rid,
            "level": "error" if f.get("severe") else "warning",
            "message": {"text": f.get("message") or rid},
            "locations": [{"physicalLocation": physical}],
            "properties": {"severity": str(f.get("severity") or "n/d")},
        })
    return {
        "version": "2.1.0",
        "$schema": SARIF_SCHEMA,
        "runs": [{
            "tool": {"driver": {"name": tool, "rules": list(rules.values())}},
            "results": results,
        }]
    }

def summarize_trivy(path: Path):
    findings = []
    severe = False
    if not path.is_file() or path.stat().st_size == 0:
        return findings, severe
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return findings, severe
    for result in data.get("Results", []) or []:
        target = result.get("Target") or "filesystem"
        for v in result.get("Vulnerabilities", []) or []:
            sev = str(v.get("Severity") or "UNKNOWN").upper()
            high = sev in {"HIGH", "CRITICAL"}
            severe = severe or high
            pkg = v.get("PkgName") or "dependency"
            installed = v.get("InstalledVersion") or "?"
            fixed = v.get("FixedVersion") or "sin versión corregida indicada"
            findings.append({
                "rule": v.get("VulnerabilityID") or "trivy-vulnerability",
                "message": f"{pkg} {installed}: {v.get('Title') or v.get('Description') or 'vulnerabilidad'} · fix: {fixed}",
                "location": target,
                "severity": sev,
                "severe": high,
            })
    return findings, severe

def summarize_cppcheck(path: Path):
    findings = []
    severe = False
    if not path.is_file() or path.stat().st_size == 0:
        return findings, severe
    try:
        root = ET.parse(path).getroot()
    except Exception:
        return findings, severe
    for error in root.findall(".//error"):
        sev = (error.get("severity") or "warning").lower()
        high = sev == "error"
        severe = severe or high
        loc = error.find("location")
        location = ""
        if loc is not None:
            location = loc.get("file") or ""
            if loc.get("line"):
                location += f":{loc.get('line')}"
        findings.append({
            "rule": error.get("id") or "cppcheck",
            "message": error.get("msg") or error.get("verbose") or "cppcheck finding",
            "location": location,
            "severity": sev,
            "severe": high,
        })
    return findings, severe

def summarize_ruff(path: Path):
    findings = []
    if not path.is_file() or path.stat().st_size == 0:
        return findings, False
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return findings, False
    for item in data if isinstance(data, list) else []:
        loc = item.get("location") or {}
        filename = item.get("filename") or ""
        location = filename
        if loc.get("row"):
            location += f":{loc['row']}:{loc.get('column', 1)}"
        findings.append({
            "rule": item.get("code") or "ruff",
            "message": item.get("message") or "ruff finding",
            "location": location,
            "severity": "warning",
            "severe": False,
        })
    return findings, False

def write_markdown(tool, findings, severe, exit_code, out_dir: Path):
    status = "OK" if exit_code == 0 else f"CLI exit {exit_code}"
    lines = [
        f"## {tool}",
        "",
        f"- Estado de la herramienta: **{status}**",
        f"- Hallazgos: **{len(findings)}**",
        f"- Hallazgos graves: **{'sí' if severe else 'no'}**",
        f"- Recomendación: {RECOMMENDATIONS.get(tool, 'Revisar los hallazgos y corregir la causa raíz.')}",
        "",
    ]
    if findings:
        lines.append("Hallazgos principales:")
        lines.append("")
        for f in findings[:20]:
            marker = "GRAVE" if f.get("severe") else "info"
            where = f" · {f['location']}" if f.get("location") else ""
            msg = " ".join((f.get("message") or "").split())
            if len(msg) > 280:
                msg = msg[:277] + "..."
            lines.append(f"- **[{marker}] {f.get('rule','finding')}** ({f.get('severity','n/d')}){where}: {msg}")
        lines.append("")
    (out_dir / f"{tool}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (out_dir / f"{tool}.severe").write_text("1\n" if severe else "0\n", encoding="utf-8")
    (out_dir / f"{tool}.count").write_text(str(len(findings)) + "\n", encoding="utf-8")
    (out_dir / f"{tool}.status").write_text(str(exit_code) + "\n", encoding="utf-8")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tool", required=True, choices=["semgrep", "gitleaks", "trivy", "detekt", "cppcheck", "ruff"])
    ap.add_argument("--format", required=True, choices=["sarif", "trivy", "cppcheck", "ruff"])
    ap.add_argument("--input", required=True)
    ap.add_argument("--out-dir", default="results")
    ap.add_argument("--exit-code", type=int, default=0)
    args = ap.parse_args()

    tool = args.tool
    input_path = Path(args.input)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.format == "sarif":
        sarif, findings, severe = summarize_sarif(tool, input_path)
    elif args.format == "trivy":
        findings, severe = summarize_trivy(input_path)
        sarif = sarif_from_findings(tool, findings)
    elif args.format == "cppcheck":
        findings, severe = summarize_cppcheck(input_path)
        sarif = sarif_from_findings(tool, findings)
    else:
        findings, severe = summarize_ruff(input_path)
        sarif = sarif_from_findings(tool, findings)

    write_json(out_dir / f"{tool}.sarif", sarif)
    write_markdown(tool, findings, severe, args.exit_code, out_dir)

if __name__ == "__main__":
    main()
