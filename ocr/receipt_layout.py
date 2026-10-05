def merge_boxes_into_rows(boxes):
    rows = []
    indexed_boxes = [dict(item, detectionIndex=index) for index, item in enumerate(boxes)]
    indexed_boxes.sort(key=lambda item: (item["top"], item["left"]))

    for box in indexed_boxes:
        center = box["top"] + box["height"] / 2
        matching_row = None
        closest_distance = float("inf")

        for row in rows:
            # Keep the tolerance proportional to the OCR text size. A fixed
            # pixel minimum joins separate products after image downscaling.
            threshold = max(row["height"], box["height"]) * 0.45
            distance = abs(center - row["center"])
            if distance > threshold or distance >= closest_distance:
                continue
            if any(horizontally_overlaps(box, item) for item in row["boxes"]):
                continue
            if any(vertical_overlap(box, item) >= 0.5 for item in row["boxes"]):
                matching_row = row
                closest_distance = distance

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


def horizontally_overlaps(first, second):
    overlap = min(first["right"], second["right"]) - max(first["left"], second["left"])
    smaller_width = min(first["right"] - first["left"], second["right"] - second["left"])
    return smaller_width > 0 and overlap >= smaller_width * 0.5


def vertical_overlap(first, second):
    overlap = max(0, min(first["bottom"], second["bottom"]) - max(first["top"], second["top"]))
    smaller_height = min(first["height"], second["height"])
    return overlap / smaller_height if smaller_height > 0 else 0
