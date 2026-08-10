import json
import math
import os
from io import BytesIO
from pathlib import Path
import tempfile
from typing import Dict, Any
import urllib.request

import pandas as pd


DATASETS = {
    "wfa_boys": {
        "url": "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/wfa_boys_0-to-5-years_zscores.xlsx?sfvrsn=97a05331_9",
        "age_col": "Month",
        "unit": "month",
    },
    "wfa_girls": {
        "url": "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/wfa_girls_0-to-5-years_zscores.xlsx?sfvrsn=4c03b8db_7",
        "age_col": "Month",
        "unit": "month",
    },
    "hcfa_boys": {
        "url": "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/head-circumference-for-age/hcfa-boys-0-5-zscores.xlsx?sfvrsn=adf57aa4_8",
        "age_col": "Month",
        "unit": "month",
    },
    "hcfa_girls": {
        "url": "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/head-circumference-for-age/hcfa-girls-0-5-zscores.xlsx?sfvrsn=8f959f88_6",
        "age_col": "Month",
        "unit": "month",
    },
    "lhfa_boys": {
        "url": "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/length-height-for-age/expandable-tables/lhfa-boys-zscore-expanded-tables.xlsx?sfvrsn=7b4a3428_12",
        "age_col": "Day",
        "unit": "day",
    },
    "lhfa_girls": {
        "url": "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/length-height-for-age/expandable-tables/lhfa-girls-zscore-expanded-tables.xlsx?sfvrsn=27f1e2cb_10",
        "age_col": "Day",
        "unit": "day",
    },
}


def download_excel(url: str) -> pd.DataFrame:
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    xls = pd.ExcelFile(BytesIO(data))
    return xls.parse(xls.sheet_names[0])


def build_table(df: pd.DataFrame, age_col: str, unit: str) -> Dict[str, Any]:
    required_columns = {age_col, "L", "M", "S"}
    missing_columns = required_columns.difference(df.columns)
    if missing_columns:
        raise ValueError(f"WHO 数据缺少列：{', '.join(sorted(missing_columns))}")

    table = df[[age_col, "L", "M", "S"]].dropna()
    rows = []
    for _, row in table.iterrows():
        values = {
            "age": float(row[age_col]),
            "l": float(row["L"]),
            "m": float(row["M"]),
            "s": float(row["S"]),
        }
        if not all(math.isfinite(value) for value in values.values()):
            raise ValueError("WHO 数据包含非有限数值")
        rows.append(values)

    if not rows:
        raise ValueError("WHO 数据集为空")
    ages = [row["age"] for row in rows]
    if ages != sorted(ages) or len(ages) != len(set(ages)):
        raise ValueError("WHO 年龄序列必须严格递增且不能重复")
    return {"unit": unit, "rows": rows}


def main() -> None:
    output_dir = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "who_growth"
    # 先下载并验证全部数据，任何一个数据集失败都不改动现有资产。
    generated_tables = {}
    for name, config in DATASETS.items():
        df = download_excel(config["url"])
        generated_tables[name] = build_table(df, config["age_col"], config["unit"])

    output_dir.mkdir(parents=True, exist_ok=True)
    # 临时目录位于目标目录内，确保 os.replace 在同一文件系统上完成原子替换。
    with tempfile.TemporaryDirectory(prefix="who_growth_", dir=output_dir) as temp_dir:
        staged_paths = {}
        for name, table in generated_tables.items():
            staged_path = Path(temp_dir) / f"{name}.json"
            with staged_path.open("w", encoding="utf-8") as file:
                json.dump(table, file, ensure_ascii=True)
                file.flush()
                os.fsync(file.fileno())
            staged_paths[name] = staged_path

        for name, staged_path in staged_paths.items():
            output_path = output_dir / f"{name}.json"
            os.replace(staged_path, output_path)
            print(f"generated: {output_path}")


if __name__ == "__main__":
    main()
