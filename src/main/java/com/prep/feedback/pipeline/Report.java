package com.prep.feedback.pipeline;

/**
 * @param dropped           blank text or duplicate (removed during ingest)
 * @param skippedAlreadyDone already in the journal from a previous run
 * @param succeeded         classified in THIS run
 * @param deadLettered      dead-lettered in THIS run
 */
public record Report(int dropped, int skippedAlreadyDone, int succeeded, int deadLettered) {}
