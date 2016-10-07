/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.compiler;

import com.intellij.compiler.server.BuildManagerListener;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.containers.ConcurrentFactoryMap;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.FileBasedIndex;
import gnu.trove.THashSet;
import gnu.trove.TIntHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

import static com.intellij.psi.search.GlobalSearchScope.*;

public class CompilerReferenceServiceImpl extends CompilerReferenceService implements ModificationTracker {
  private final ProjectFileIndex myProjectFileIndex;
  private final Set<Module> myChangedModules = ContainerUtil.newConcurrentSet();
  private final Set<FileType> myFileTypes;
  private final LongAdder myCompilationCount = new LongAdder();

  private volatile CompilerReferenceReader myReader;
  private volatile GlobalSearchScope myDirtyScope = EMPTY_SCOPE;

  private final Object myLock = new Object();

  public CompilerReferenceServiceImpl(Project project) {
    super(project);
    myProjectFileIndex = ProjectRootManager.getInstance(project).getFileIndex();
    myFileTypes = Collections.unmodifiableSet(ContainerUtil.set(JavaFileType.INSTANCE));
  }

  @Override
  public void projectOpened() {
    if (isEnabled()) {
      myProject.getMessageBus().connect(myProject).subscribe(BuildManagerListener.TOPIC, new BuildManagerListener() {
        @Override
        public void beforeBuildProcessStarted(Project project, UUID sessionId) {
        }

        @Override
        public void buildStarted(Project project, UUID sessionId, boolean isAutomake) {
          closeReaderIfNeed();
        }

        @Override
        public void buildFinished(Project project, UUID sessionId, boolean isAutomake) {
          myChangedModules.clear();
          myDirtyScope = EMPTY_SCOPE;
          myCompilationCount.increment();
          openReaderIfNeed();
        }
      });

      VirtualFileManager.getInstance().addVirtualFileListener(new VirtualFileAdapter() {
        @Override
        public void fileCreated(@NotNull VirtualFileEvent event) {
          processChange(event.getFile());
        }

        @Override
        public void fileCopied(@NotNull VirtualFileCopyEvent event) {
          processChange(event.getFile());
        }

        @Override
        public void fileMoved(@NotNull VirtualFileMoveEvent event) {
          processChange(event.getFile());
        }

        @Override
        public void beforePropertyChange(@NotNull VirtualFilePropertyEvent event) {
          if (VirtualFile.PROP_NAME.equals(event.getPropertyName()) || VirtualFile.PROP_SYMLINK_TARGET.equals(event.getPropertyName())) {
            processChange(event.getFile());
          }
        }

        @Override
        public void beforeContentsChange(@NotNull VirtualFileEvent event) {
          processChange(event.getFile());
        }

        @Override
        public void beforeFileDeletion(@NotNull VirtualFileEvent event) {
          processChange(event.getFile());
        }

        @Override
        public void beforeFileMovement(@NotNull VirtualFileMoveEvent event) {
          processChange(event.getFile());
        }

        private void processChange(VirtualFile file) {
          if (myReader != null && myProjectFileIndex.isInSourceContent(file) && myFileTypes.contains(file.getFileType())) {
            final Module module = myProjectFileIndex.getModuleForFile(file);
            if (module != null) {
              if (myChangedModules.add(module)) {
                myDirtyScope = myDirtyScope.union(module.getModuleWithDependentsScope());
              }
            }
          }
        }
      }, myProject);
    }

  }

  @Override
  public void projectClosed() {
    closeReaderIfNeed();
  }

  @Nullable
  @Override
  public GlobalSearchScope getScopeWithoutCodeReferences(@NotNull PsiElement element, @NotNull CompilerSearchAdapter adapter) {
    if (!isServiceEnabled()) return null;

    return CachedValuesManager.getCachedValue(element,
                                              () -> CachedValueProvider.Result.create(new ConcurrentFactoryMap<CompilerSearchAdapter, GlobalSearchScope>() {
                                                  @Nullable
                                                  @Override
                                                  protected GlobalSearchScope create(CompilerSearchAdapter key) {
                                                    return calculateScopeWithoutReferences(element, key);
                                                  }
                                              },
                                              PsiModificationTracker.MODIFICATION_COUNT, this)).get(adapter);
  }

  private boolean isServiceEnabled() {
    return myReader != null && isEnabled();
  }

  @Nullable
  private GlobalSearchScope calculateScopeWithoutReferences(@NotNull PsiElement element, CompilerSearchAdapter adapter) {
    TIntHashSet referentFileIds = getReferentFileIds(element, adapter);
    if (referentFileIds == null) return null;

    return getScopeRestrictedByFileTypes(new ScopeWithoutReferencesOnCompilation(referentFileIds).intersectWith(notScope(myDirtyScope)),
                                         myFileTypes.toArray(new FileType[myFileTypes.size()]));
  }

  @Nullable
  private TIntHashSet getReferentFileIds(@NotNull PsiElement element, @NotNull CompilerSearchAdapter adapter) {
    final PsiFile file = element.getContainingFile();
    if (file == null) return null;
    final VirtualFile vFile = file.getVirtualFile();
    if (vFile == null) return null;

    ElementPlace place = ElementPlace.get(vFile, myProjectFileIndex);
    if (place == null) {
      return null;
    }

    if (myDirtyScope.contains(vFile)) {
      return null;
    }
    CompilerElement[] compilerElements;
    if (place == ElementPlace.SRC) {
      final CompilerElement compilerElement = adapter.asCompilerElement(element);
      compilerElements = compilerElement == null ? CompilerElement.EMPTY_ARRAY : new CompilerElement[]{compilerElement};
    }
    else {
      compilerElements = adapter.libraryElementAsCompilerElements(element);
    }
    if (compilerElements.length == 0) return null;

    synchronized (myLock) {
      if (myReader == null) return null;
      TIntHashSet referentFileIds = new TIntHashSet();
      for (CompilerElement compilerElement : compilerElements) {
        final TIntHashSet referents = myReader.findReferentFileIds(compilerElement, adapter);
        if (referents == null) return null;
        referentFileIds.addAll(referents.toArray());
      }
      return referentFileIds;
    }
  }

  private void closeReaderIfNeed() {
    synchronized (myLock) {
      if (myReader != null) {
        myReader.close();
        myReader = null;
      }
    }
  }

  private void openReaderIfNeed() {
    synchronized (myLock) {
      if (myProject.isOpen()) {
        myReader = CompilerReferenceReader.create(myProject);
      }
    }
  }

  @TestOnly
  @Nullable
  public Set<VirtualFile> getReferentFiles(@NotNull PsiElement element, @NotNull CompilerSearchAdapter adapter) {
    FileBasedIndex fileIndex = FileBasedIndex.getInstance();
    final TIntHashSet ids = getReferentFileIds(element, adapter);
    if (ids == null) return null;
    Set<VirtualFile> fileSet = new THashSet<>();
    ids.forEach(id -> {
      final VirtualFile vFile = fileIndex.findFileById(myProject, id);
      assert vFile != null;
      fileSet.add(vFile);
      return true;
    });
    return fileSet;
  }

  private enum ElementPlace {
    SRC, LIB;

    private static ElementPlace get(VirtualFile file, ProjectFileIndex index) {
      return index.isInSourceContent(file) ? SRC :
             ((index.isInLibrarySource(file) || index.isInLibraryClasses(file)) ? LIB : null);
    }
  }

  private static class ScopeWithoutReferencesOnCompilation extends GlobalSearchScope {
    private final TIntHashSet myReferentIds;

    private ScopeWithoutReferencesOnCompilation(TIntHashSet ids) {
      myReferentIds = ids;
    }

    @Override
    public boolean contains(@NotNull VirtualFile file) {
      return !(file instanceof VirtualFileWithId) || !myReferentIds.contains(((VirtualFileWithId)file).getId());
    }

    @Override
    public int compare(@NotNull VirtualFile file1, @NotNull VirtualFile file2) {
      return 0;
    }

    @Override
    public boolean isSearchInModuleContent(@NotNull Module aModule) {
      return true;
    }

    @Override
    public boolean isSearchInLibraries() {
      return false;
    }
  }

  @Override
  public long getModificationCount() {
    return myCompilationCount.longValue();
  }
}
