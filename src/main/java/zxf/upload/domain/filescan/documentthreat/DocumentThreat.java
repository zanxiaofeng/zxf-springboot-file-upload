package zxf.upload.domain.filescan.documentthreat;

/**
 * 文档威胁检出（DocumentThreat 域）。kind 供管道按策略分级处置。
 */
public record DocumentThreat(String description, ThreatKind kind) {}
