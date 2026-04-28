package com.example.drones.detection

/**
 * ALL model-specific configuration lives here.
 * Swapping the model = only edit this file.
 *
 * Current model: EfficientDet-Lite0, COCO 80-class, float32, 320x320
 * Source: TFHub SavedModel → TFLite (13 MB)
 */
object DetectorConfig {

    const val MODEL_FILE     = "efficientdet_lite0.tflite"
    const val SCORE_THRESHOLD = 0.05f   // raise to 0.30 once detection confirmed working
    const val MAX_RESULTS    = 5

    enum class Normalization { ZERO_TO_ONE, NEG_ONE_TO_ONE, RAW_BYTES }

    // float32 TFHub EfficientDet-Lite0 uses [0, 1]
    val NORMALIZATION = Normalization.ZERO_TO_ONE

    // COCO 80-class labels, 0-indexed (model outputs 1-indexed — TFLiteRunner subtracts 1)
    val LABELS = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
        "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
        "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
        "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
        "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
        "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
        "hair drier", "toothbrush"
    )
}
