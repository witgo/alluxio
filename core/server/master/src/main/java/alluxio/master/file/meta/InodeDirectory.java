/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.file.meta;

import alluxio.Configuration;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.collections.CompositeUniqueFieldIndex;
import alluxio.collections.IndexDefinition;
import alluxio.exception.InvalidPathException;
import alluxio.master.ProtobufUtils;
import alluxio.master.file.options.CreateDirectoryOptions;
import alluxio.proto.journal.File.InodeDirectoryEntry;
import alluxio.proto.journal.File.UpdateInodeDirectoryEntry;
import alluxio.proto.journal.Journal.JournalEntry;
import alluxio.security.authorization.AccessControlList;
import alluxio.security.authorization.DefaultAccessControlList;
import alluxio.wire.FileInfo;

import java.util.Collection;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Alluxio file system's directory representation in the file system master. The inode must be
 * locked ({@link #lockRead()} or {@link #lockWrite()}) before methods are called.
 */
@NotThreadSafe
public final class InodeDirectory extends Inode<InodeDirectory> implements InodeDirectoryView {
  private static final IndexDefinition<InodeView, String> NAME_INDEX =
      new IndexDefinition<InodeView, String>(true) {
        @Override
        public String getFieldValue(InodeView o) {
          return o.getName();
        }
      };

  // Use map to store objects when the number of objects exceeds
  // MAP_THRESHOLD, otherwise use list to store objects.
  private static final int MAP_THRESHOLD =
      Configuration.getInt(PropertyKey.MASTER_METE_DATE_INODE_DIRECTORY_MAP_THRESHOLD);

  /** Use UniqueFieldIndex directly for name index rather than using IndexedSet. */
  private final CompositeUniqueFieldIndex<InodeView, String> mChildren =
      new CompositeUniqueFieldIndex<>(NAME_INDEX, MAP_THRESHOLD);

  private boolean mMountPoint;

  private boolean mDirectChildrenLoaded;

  private DefaultAccessControlList mDefaultAcl;

  /**
   * Creates a new instance of {@link InodeDirectory}.
   *
   * @param id the id to use
   */
  private InodeDirectory(long id) {
    super(id, true);
    mMountPoint = false;
    mDirectChildrenLoaded = false;
    mDefaultAcl = new DefaultAccessControlList(mAcl);
  }

  @Override
  protected InodeDirectory getThis() {
    return this;
  }

  /**
   * Adds the given inode to the set of children.
   *
   * @param child the inode to add
   * @return true if inode was added successfully, false otherwise
   */
  public boolean addChild(Inode<?> child) {
    return mChildren.add(child);
  }

  @Override
  public InodeView getChild(String name) {
    return mChildren.getFirst(name);
  }

  @Override
  @Nullable
  public InodeView getChildReadLock(String name, InodeLockList lockList) throws
      InvalidPathException {
    while (true) {
      InodeView child = mChildren.getFirst(name);
      if (child == null) {
        return null;
      }
      lockList.lockReadAndCheckParent(child, this);
      if (mChildren.getFirst(name) != child) {
        // The locked child has changed, so unlock and try again.
        lockList.unlockLast();
        continue;
      }
      return child;
    }
  }

  @Override
  @Nullable
  public InodeView getChildWriteLock(String name, InodeLockList lockList) throws
      InvalidPathException {
    while (true) {
      InodeView child = mChildren.getFirst(name);
      if (child == null) {
        return null;
      }
      lockList.lockWriteAndCheckParent(child, this);
      if (mChildren.getFirst(name) != child) {
        // The locked child has changed, so unlock and try again.
        lockList.unlockLast();
        continue;
      }
      return child;
    }
  }

  @Override
  public Collection<InodeView> getChildren() {
    return mChildren.readOnlyValues();
  }

  @Override
  public Collection<Long> getChildrenIds() {
    Collection<InodeView> iterator = getChildren();
    return iterator.stream().map(inode -> inode.getId()).collect(Collectors.toList());
  }

  @Override
  public int getNumberOfChildren() {
    return mChildren.size();
  }

  @Override
  public boolean isMountPoint() {
    return mMountPoint;
  }

  @Override
  public synchronized boolean isDirectChildrenLoaded() {
    return mDirectChildrenLoaded;
  }

  @Override
  public synchronized boolean areDescendantsLoaded() {
    if (!isDirectChildrenLoaded()) {
      return false;
    }
    for (InodeView inode : getChildren()) {
      if (inode.isDirectory()) {
        InodeDirectory inodeDirectory = (InodeDirectory) inode;
        if (!inodeDirectory.areDescendantsLoaded()) {
          return false;
        }
      }
    }
    return true;
  }

  @Override
  public DefaultAccessControlList getDefaultACL() {
    return mDefaultAcl;
  }

  /**
   * Removes the given inode from the directory.
   *
   * @param child the Inode to remove
   * @return true if the inode was removed, false otherwise
   */
  public boolean removeChild(Inode<?> child) {
    return mChildren.remove(child);
  }

  /**
   * Removes the given child by its name from the directory.
   *
   * @param name the name of the Inode to remove
   * @return true if the inode was removed, false otherwise
   */
  public boolean removeChild(String name) {
    InodeView child = mChildren.getFirst(name);
    return mChildren.remove(child);
  }

  /**
   * @param mountPoint the mount point flag value to use
   * @return the updated object
   */
  public InodeDirectory setMountPoint(boolean mountPoint) {
    mMountPoint = mountPoint;
    return getThis();
  }

  /**
   * @param directChildrenLoaded whether to load the direct children if they were not loaded before
   * @return the updated object
   */
  public synchronized InodeDirectory setDirectChildrenLoaded(boolean directChildrenLoaded) {
    mDirectChildrenLoaded = directChildrenLoaded;
    return getThis();
  }

  @Override
  public InodeDirectory setDefaultACL(DefaultAccessControlList acl) {
    mDefaultAcl = acl;
    return getThis();
  }

  /**
   * Generates client file info for a folder.
   *
   * @param path the path of the folder in the filesystem
   * @return the generated {@link FileInfo}
   */
  @Override
  public FileInfo generateClientFileInfo(String path) {
    FileInfo ret = new FileInfo();
    ret.setFileId(getId());
    ret.setName(getName());
    ret.setPath(path);
    ret.setLength(mChildren.size());
    ret.setBlockSizeBytes(0);
    ret.setCreationTimeMs(getCreationTimeMs());
    ret.setCompleted(true);
    ret.setFolder(isDirectory());
    ret.setPinned(isPinned());
    ret.setCacheable(false);
    ret.setPersisted(isPersisted());
    ret.setLastModificationTimeMs(getLastModificationTimeMs());
    ret.setTtl(mTtl);
    ret.setTtlAction(mTtlAction);
    ret.setOwner(getOwner());
    ret.setGroup(getGroup());
    ret.setMode(getMode());
    ret.setPersistenceState(getPersistenceState().toString());
    ret.setMountPoint(isMountPoint());
    ret.setUfsFingerprint(Constants.INVALID_UFS_FINGERPRINT);
    ret.setAcl(mAcl);
    ret.setDefaultAcl(mDefaultAcl);
    return ret;
  }

  /**
   * Updates this inode directory's state from the given entry.
   *
   * @param entry the entry
   */
  public void updateFromEntry(UpdateInodeDirectoryEntry entry) {
    if (entry.hasDefaultAcl()) {
      setDefaultACL(
          (DefaultAccessControlList) DefaultAccessControlList.fromProtoBuf(entry.getDefaultAcl()));
    }
    if (entry.hasDirectChildrenLoaded()) {
      setDirectChildrenLoaded(entry.getDirectChildrenLoaded());
    }
    if (entry.hasMountPoint()) {
      setMountPoint(entry.getMountPoint());
    }
  }

  @Override
  public String toString() {
    return toStringHelper().add("mountPoint", mMountPoint).add("children", mChildren).toString();
  }

  /**
   * Converts the entry to an {@link InodeDirectory}.
   *
   * @param entry the entry to convert
   * @return the {@link InodeDirectory} representation
   */
  public static InodeDirectory fromJournalEntry(InodeDirectoryEntry entry) {
    // If journal entry has no mode set, set default mode for backwards-compatibility.
    InodeDirectory ret = new InodeDirectory(entry.getId())
        .setCreationTimeMs(entry.getCreationTimeMs())
        .setName(entry.getName())
        .setParentId(entry.getParentId())
        .setPersistenceState(PersistenceState.valueOf(entry.getPersistenceState()))
        .setPinned(entry.getPinned())
        .setLastModificationTimeMs(entry.getLastModificationTimeMs(), true)
        .setMountPoint(entry.getMountPoint())
        .setTtl(entry.getTtl())
        .setTtlAction(ProtobufUtils.fromProtobuf(entry.getTtlAction()))
        .setDirectChildrenLoaded(entry.getDirectChildrenLoaded());
    if (entry.hasAcl()) {
      ret.mAcl = AccessControlList.fromProtoBuf(entry.getAcl());
    } else {
      // Backward compatibility.
      AccessControlList acl = new AccessControlList();
      acl.setOwningUser(entry.getOwner());
      acl.setOwningGroup(entry.getGroup());
      short mode = entry.hasMode() ? (short) entry.getMode() : Constants.DEFAULT_FILE_SYSTEM_MODE;
      acl.setMode(mode);
      ret.mAcl = acl;
    }
    if (entry.hasDefaultAcl()) {
      ret.mDefaultAcl = (DefaultAccessControlList) AccessControlList
          .fromProtoBuf(entry.getDefaultAcl());
    } else {
      ret.mDefaultAcl = new DefaultAccessControlList();
    }
    return ret;
  }

  /**
   * Creates an {@link InodeDirectory}.
   *
   * @param id id of this inode
   * @param parentId id of the parent of this inode
   * @param name name of this inode
   * @param options options to create this directory
   * @return the {@link InodeDirectory} representation
   */
  public static InodeDirectory create(long id, long parentId, String name,
      CreateDirectoryOptions options) {
    return new InodeDirectory(id)
        .setParentId(parentId)
        .setName(name)
        .setTtl(options.getTtl())
        .setTtlAction(options.getTtlAction())
        .setOwner(options.getOwner())
        .setGroup(options.getGroup())
        .setMode(options.getMode().toShort())
        .setAcl(options.getAcl())
        // SetAcl call is also setting default AclEntries
        .setAcl(options.getDefaultAcl())
        .setMountPoint(options.isMountPoint());
  }

  @Override
  public JournalEntry toJournalEntry() {
    InodeDirectoryEntry inodeDirectory = InodeDirectoryEntry.newBuilder()
        .setCreationTimeMs(getCreationTimeMs())
        .setId(getId())
        .setName(getName())
        .setParentId(getParentId())
        .setPersistenceState(getPersistenceState().name())
        .setPinned(isPinned())
        .setLastModificationTimeMs(getLastModificationTimeMs())
        .setMountPoint(isMountPoint())
        .setTtl(getTtl())
        .setTtlAction(ProtobufUtils.toProtobuf(getTtlAction()))
        .setDirectChildrenLoaded(isDirectChildrenLoaded())
        .setAcl(AccessControlList.toProtoBuf(mAcl))
        .setDefaultAcl(AccessControlList.toProtoBuf(mDefaultAcl))
        .build();
    return JournalEntry.newBuilder().setInodeDirectory(inodeDirectory).build();
  }
}
