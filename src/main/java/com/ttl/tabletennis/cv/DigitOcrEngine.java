package com.ttl.tabletennis.cv;

import java.awt.image.BufferedImage;
import java.util.Optional;

public interface DigitOcrEngine {

    String readerName();

    Optional<DigitOcrRecognition> recognize(String fieldName, BufferedImage fieldImage);
}
