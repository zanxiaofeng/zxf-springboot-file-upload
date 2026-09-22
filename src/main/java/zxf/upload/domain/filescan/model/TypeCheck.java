package zxf.upload.domain.filescan.model;

/**
 * 类型校验结论（VirusScan 域）。detectedMime 供管道后续阶段复用，避免重复探测。
 */
public record TypeCheck(boolean passed, String detectedMime, String rejectReason) {}
