package io.takari.maven.plugins.compile.jdt;

import java.util.Iterator;
import java.util.ServiceLoader;

import javax.annotation.processing.Processor;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import org.eclipse.jdt.internal.compiler.apt.dispatch.BaseAnnotationProcessorManager;
import org.eclipse.jdt.internal.compiler.apt.dispatch.ProcessorInfo;
import org.eclipse.jdt.internal.compiler.problem.AbortCompilation;

import io.takari.incrementalbuild.MessageSeverity;
import io.takari.maven.plugins.compile.CompilerBuildContext;

// TODO reconcile with BatchAnnotationProcessorManager
class AnnotationProcessorManager extends BaseAnnotationProcessorManager {

  private final CompilerBuildContext context;

  private static interface ResettableProcessorIterator extends Iterator<Processor> {

    void reset();

  }

  private final ResettableProcessorIterator processors;

  private static class SpecifiedProcessors implements ResettableProcessorIterator {

    private final ClassLoader loader;
    private final String[] processors;
    private int idx;

    public SpecifiedProcessors(ClassLoader loader, String[] processors) {
      this.loader = loader;
      this.processors = processors;
    }

    @Override
    public boolean hasNext() {
      return idx < processors.length;
    }

    @Override
    public Processor next() {
      try {
        return (Processor) loader.loadClass(processors[idx++]).newInstance();
      } catch (ReflectiveOperationException e) {
        // TODO: better error handling
        throw new AbortCompilation(null, e);
      }
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void reset() {
      idx = 0;
    }
  }

  private static class DiscoveredProcessors implements ResettableProcessorIterator {

    private final ServiceLoader<Processor> loader;
    private Iterator<Processor> iterator;

    public DiscoveredProcessors(ClassLoader procLoader) {
      this.loader = ServiceLoader.load(Processor.class, procLoader);
      this.iterator = loader.iterator();
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Processor next() {
      return iterator.next();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void reset() {
      this.iterator = loader.iterator();
    }

  }

  public AnnotationProcessorManager(CompilerBuildContext context, ProcessingEnvImpl processingEnv, StandardJavaFileManager fileManager, String[] processors) {
    this.context = context;
    this._processingEnv = processingEnv;
    ClassLoader procLoader = fileManager.getClassLoader(StandardLocation.ANNOTATION_PROCESSOR_PATH);
    this.processors = processors != null //
        ? new SpecifiedProcessors(procLoader, processors) //
        : new DiscoveredProcessors(procLoader);
  }

  @Override
  public ProcessorInfo discoverNextProcessor() {
    if (processors.hasNext()) {
      Processor processor = processors.next();
      processor.init(_processingEnv);
      ProcessorInfo procecssorInfo = new ProcessorInfo(processor);
      _processors.add(procecssorInfo); // TODO this needs to happen in RoundDispatcher.round()
      return procecssorInfo;
    }
    return null;
  }

  @Override
  public void reportProcessorException(Processor p, Exception e) {
    String msg = String.format("Exception executing annotation processor %s: %s", p.getClass().getName(), e.getMessage());
    context.addPomMessage(msg, MessageSeverity.ERROR, e);
    throw new AbortCompilation(null, e);
  }

  /**
   * Resets this annotation processor manager between incremental compiler loop iterations.
   */
  public void hardReset() {
    // clear/reset parent state
    ((ProcessingEnvImpl) _processingEnv).hardReset();
    _processors.clear();
    _isFirstRound = true;
    _round = 0;
    // clear/reset this class state
    processors.reset();
  }
}
