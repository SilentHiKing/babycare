import json
import os
import re
import tempfile
import traceback
from pathlib import Path

import pandas as pd


LANGUAGE_MAPPING = {
    "简体中文": "zh-Hans",
    "English": "en",
    "繁体中文": "zh-Hant",
    "日文": "ja",
    "韩文": "ko",
    "德语": "de",
    "法语": "fr",
    "阿语": "ar",
    "意大利语": "it",
    "波兰语": "pl",
    "葡语": "pt",
    "西语": "es",
    "俄语": "ru",
    "马来语": "ms",
    "泰语": "th",
    "越南语": "vi",
    "乌克兰语": "uk",
    "印度尼西亚语": "id",
    "土耳其语": "tr",
    "希腊语": "el",
    "捷克语": "cs",
    "荷兰语": "nl",
    "丹麦语": "da",
    "匈牙利语": "hu",
    "博克马尔挪威语": "nb",
    "瑞典语": "sv",
    "芬兰语": "fi",
}
REQUIRED_COLUMNS = ("简体中文", "English", "繁体中文", "日文", "韩文")
CODE_KEY_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_]*$")


def _find_header_row(raw_frame: pd.DataFrame, sheet_name: str) -> int:
    for index, row in raw_frame.iterrows():
        values = [str(cell).strip().lower() for cell in row if pd.notna(cell)]
        if "codekey" in values:
            return int(index)
    raise ValueError(f"工作表 {sheet_name} 中找不到 codeKey 表头")


def _build_sheet_data(excel_path: Path, sheet_name: str) -> list[dict]:
    raw_frame = pd.read_excel(excel_path, sheet_name=sheet_name, header=None)
    header_row = _find_header_row(raw_frame, sheet_name)
    frame = pd.read_excel(excel_path, sheet_name=sheet_name, header=header_row)
    frame.columns = frame.columns.astype(str).str.strip()

    missing_columns = [column for column in REQUIRED_COLUMNS if column not in frame.columns]
    if missing_columns:
        raise ValueError(f"工作表 {sheet_name} 缺少语言列：{', '.join(missing_columns)}")

    items: list[dict] = []
    seen_keys: set[str] = set()
    for row_number, (_, row) in enumerate(frame.iterrows(), start=header_row + 2):
        raw_key = row.get("codeKey")
        if pd.isna(raw_key) or str(raw_key).strip() in ("", "nan"):
            continue

        code_key = str(raw_key).strip()
        if not CODE_KEY_PATTERN.fullmatch(code_key):
            raise ValueError(f"{sheet_name} 第 {row_number} 行 codeKey 非法：{code_key}")
        if code_key in seen_keys:
            raise ValueError(f"{sheet_name} 存在重复 codeKey：{code_key}")
        seen_keys.add(code_key)

        language_data: dict[str, str] = {}
        for excel_language, language_code in LANGUAGE_MAPPING.items():
            if excel_language not in frame.columns:
                continue
            value = row.get(excel_language)
            if pd.notna(value) and str(value) != "":
                language_data[language_code] = str(value)

        missing_languages = [
            LANGUAGE_MAPPING[column]
            for column in REQUIRED_COLUMNS
            if LANGUAGE_MAPPING[column] not in language_data
        ]
        if missing_languages:
            raise ValueError(
                f"{sheet_name}/{code_key} 缺少翻译：{', '.join(missing_languages)}"
            )

        items.append({"codeKey": code_key, "language": language_data})

    if not items:
        raise ValueError(f"工作表 {sheet_name} 没有可生成的翻译项")
    return items


def _write_json_atomically(output_path: Path, payload: dict) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=output_path.parent,
            prefix=f"{output_path.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary_file:
            json.dump(payload, temporary_file, ensure_ascii=False, indent=2)
            temporary_file.flush()
            os.fsync(temporary_file.fileno())
            temporary_path = Path(temporary_file.name)
        os.replace(temporary_path, output_path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()


def excel_to_json(excel_file_path: str | Path, output_json_path: str | Path) -> dict:
    """校验多语言 Excel，并在全部 sheet 成功后原子生成 JSON。"""
    excel_path = Path(excel_file_path).resolve()
    output_path = Path(output_json_path).resolve()
    if not excel_path.is_file():
        raise FileNotFoundError(f"多语言 Excel 不存在：{excel_path}")

    result = {"code": 200, "message": "success", "data": {}}
    with pd.ExcelFile(excel_path) as workbook:
        for sheet_name in workbook.sheet_names:
            print(f"处理工作表: {sheet_name}")
            items = _build_sheet_data(excel_path, sheet_name)
            result["data"][sheet_name] = items
            print(f"完成 {sheet_name}: {len(items)} 个翻译项")

    _write_json_atomically(output_path, result)
    total_items = sum(len(items) for items in result["data"].values())
    print(f"转换完成：{output_path}，共 {total_items} 个翻译项")
    return result


if __name__ == "__main__":
    try:
        excel_to_json("多语言对照表.xlsx", "output.json")
    except Exception as exception:
        print(f"多语言转换失败：{exception}")
        traceback.print_exc()
        # 继续抛出异常，让 Gradle 收到非零退出码，禁止失败后伪装成功。
        raise
