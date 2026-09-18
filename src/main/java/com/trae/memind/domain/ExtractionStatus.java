package com.trae.memind.domain;

/** 抽取状态。默认写入端点是 fire-and-forget，只有同步端点会返回该状态。 */
public enum ExtractionStatus {
    ACCEPTED,
    SUCCESS,
    FAILED
}