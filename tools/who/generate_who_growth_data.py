import json
from io import BytesIO
from pathlib import Path
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
    with urllib.request.urlopen(req) as resp:
        data = resp.read()
    xls = pd.ExcelFile(BytesIO(data))
    return xls.parse(xls.sheet_names[0])


def build_table(df: pd.DataFrame, age_col: str, unit: str) -> Dict[str, Any]:
    table = df[[age_col, "L", "M", "S"]].dropna()
    rows = []
    for _, row in table.iterrows():
        rows.append(
            {
                "age": float(row[age_col]),
                "l": float(row["L"]),
                "m": float(row["M"]),
                "s": float(row["S"]),
            }
        )
    return {"unit": unit, "rows": rows}


def main() -> None:
    output_dir = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "who_growth"
    output_dir.mkdir(parents=True, exist_ok=True)

    for name, config in DATASETS.items():
        df = download_excel(config["url"])
        table = build_table(df, config["age_col"], config["unit"])
        output_path = output_dir / f"{name}.json"
        with output_path.open("w", encoding="utf-8") as f:
            json.dump(table, f, ensure_ascii=True)
        print(f"generated: {output_path}")


if __name__ == "__main__":
    main()
