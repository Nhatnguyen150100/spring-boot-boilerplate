package com.spring.app.modules.upload.services;

/**
 * Supplies the id of the user whose upload folder the current request works
 * in. Every upload, download and delete is confined to that folder.
 */
@FunctionalInterface
public interface UploadOwnerProvider {

  /** @return the current user's id, used verbatim as a folder name; never blank */
  String currentOwnerId();
}
