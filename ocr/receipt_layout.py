def merge_boxes_into_rows(boxes):
    rows = []
    indexed_boxes = [dict(item, detectionIndex=index) for index, item in enumerate(boxes)]
    indexed_boxes.sort(key=lambda item: (item["top"], item["left"]))

    for box in indexed_boxes:
        center = box["top"] + box["height"] / 2
        matching_row = None

        for row in rows:
            threshold = max(18, min(row["height"], box["height"]) * 0.45)
            if abs(center - row["center"]) <= threshold:
                matching_row = row
                break

        if matching_row is None:
            rows.append({
                "center": center,
                "height": box["height"],
                "boxes": [box],
            })
        else:
            matching_row["boxes"].append(box)
            matching_row["center"] = sum(item["top"] + item["height"] / 2 for item in matching_row["boxes"]) / len(matching_row["boxes"])
            matching_row["height"] = max(matching_row["height"], box["height"])

    lines = []
    for row in rows:
        row_boxes = sorted(row["boxes"], key=lambda item: item["left"])
        text = " ".join(item["text"] for item in row_boxes).strip()
        lines.append({
            "text": text,
            "score": min(item["confidence"] for item in row_boxes),
            "confidence": min(item["confidence"] for item in row_boxes),
            "top": min(item["top"] for item in row_boxes),
            "left": min(item["left"] for item in row_boxes),
            "right": max(item["right"] for item in row_boxes),
            "bottom": max(item["bottom"] for item in row_boxes),
            "width": max(item["right"] for item in row_boxes) - min(item["left"] for item in row_boxes),
            "height": max(item["bottom"] for item in row_boxes) - min(item["top"] for item in row_boxes),
            "detectionIndexes": [item["detectionIndex"] for item in row_boxes],
        })

    lines.sort(key=lambda item: (item["top"], item["left"]))
    return lines
