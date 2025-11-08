import pandas as pd
import json

def excel_to_json(excel_file_path, output_json_path):
    """
    将多语言 Excel 文件转换为 JSON 格式
    """
    # 读取 Excel 文件的所有 sheet
    excel_file = pd.ExcelFile(excel_file_path)

    result = {
        "code": 200,
        "message": "success",
        "data": {}
    }

    # 语言代码映射
    language_mapping = {
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
        "芬兰语": "fi"
    }

    for sheet_name in excel_file.sheet_names:
        print(f"处理工作表: {sheet_name}")

        # 首先读取原始数据，不指定表头
        df_raw = pd.read_excel(excel_file_path, sheet_name=sheet_name, header=None)

        # 查找包含 'codeKey' 的行作为表头
        header_row = None
        for idx, row in df_raw.iterrows():
            # 检查这一行是否包含 'codeKey'
            row_values = [str(cell).strip().lower() for cell in row if pd.notna(cell)]
            if any('codekey' in value for value in row_values):
                header_row = idx
                break

        if header_row is None:
            print(f"⚠️  在工作表 {sheet_name} 中找不到 codeKey，跳过")
            continue

        # 重新读取，使用正确的表头行
        df = pd.read_excel(excel_file_path, sheet_name=sheet_name, header=header_row)

        # 清理列名（去除空格等）
        df.columns = df.columns.astype(str).str.strip()

        sheet_data = []

        for index, row in df.iterrows():
            code_key = row.get('codeKey')

            # 跳过空值
            if pd.isna(code_key) or code_key == '' or code_key == 'nan':
                continue

            language_dict = {}

            # 遍历所有语言列
            for excel_lang, lang_code in language_mapping.items():
                if excel_lang in df.columns and pd.notna(row.get(excel_lang)) and row.get(excel_lang) != '':
                    language_dict[lang_code] = str(row[excel_lang])

            if language_dict:
                sheet_data.append({
                    "codeKey": str(code_key),
                    "language": language_dict
                })

        result["data"][sheet_name] = sheet_data
        print(f"✅ {sheet_name}: 处理了 {len(sheet_data)} 个翻译项")

    # 保存为 JSON 文件
    with open(output_json_path, 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    print(f"\n🎉 转换完成！JSON 文件已保存至: {output_json_path}")

    # 打印最终统计信息
    print("\n📊 最终统计:")
    total_items = 0
    for project, items in result["data"].items():
        print(f"  {project}: {len(items)} 个翻译项")
        total_items += len(items)
    print(f"  总计: {total_items} 个翻译项")

    return result

# 使用示例
if __name__ == "__main__":
    excel_file = "多语言对照表.xlsx"
    output_file = "output.json"

    try:
        result = excel_to_json(excel_file, output_file)
    except Exception as e:
        print(f"❌ 发生错误: {e}")
        import traceback
        traceback.print_exc()