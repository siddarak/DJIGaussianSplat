package com.example.drones.detection

/**
 * ALL model-specific configuration lives here.
 * Swapping the model = only edit this file.
 *
 * Current model: EfficientDet-Lite0 with TFLite_Detection_PostProcess (NMS embedded)
 * COCO 80-class, uint8 quantized, 320x320, 4 outputs (boxes/classes/scores/count)
 * Source: TFLite Task Library hosted (4.4 MB)
 * URL: https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite
 */
object DetectorConfig {

    const val MODEL_FILE     = "efficientdet_lite0.tflite"
    const val SCORE_THRESHOLD = 0.30f
    const val MAX_RESULTS    = 5

    // Only show objects worth orbiting (big, stationary). Filters out
    // frisbees, food, utensils, phones, etc. that appear on a chair.
    val ALLOWED_LABELS = setOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
        "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
        "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet",
        "tv", "laptop", "microwave", "oven", "refrigerator"
    )

    enum class Normalization { ZERO_TO_ONE, NEG_ONE_TO_ONE, RAW_BYTES }

    // uint8 quantized model takes raw bytes [0, 255]
    val NORMALIZATION = Normalization.RAW_BYTES

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
