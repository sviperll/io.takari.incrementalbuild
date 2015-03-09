package io.takari.incrementalbuild.spi;

import io.takari.incrementalbuild.BuildContext;
import io.takari.incrementalbuild.ResourceStatus;
import io.takari.incrementalbuild.workspace.Workspace2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DefaultBuildContext extends AbstractBuildContext implements BuildContext {

  public DefaultBuildContext(BuildContextEnvironment configuration) {
    super(configuration);
  }

  @Override
  public Iterable<DefaultResource<File>> registerAndProcessInputs(File basedir,
      Collection<String> includes, Collection<String> excludes) throws IOException {
    basedir = normalize(basedir);
    final List<DefaultResource<File>> inputs = new ArrayList<DefaultResource<File>>();
    for (DefaultResourceMetadata<File> metadata : registerInputs(basedir, includes, excludes)) {
      if (metadata.getStatus() != ResourceStatus.UNMODIFIED) {
        inputs.add(metadata.process());
      }
    }
    return inputs;
  }

  @Override
  protected void finalizeContext() throws IOException {

    // only supports simple input --> output associations
    // outputs are carried over iff their input is carried over

    // TODO harden the implementation
    //
    // things can get tricky even with such simple model. consider the following
    // build-1: inputA --> outputA
    // build-2: inputA unchanged. inputB --> outputA
    // now outputA has multiple inputs, which is not supported by this context
    //
    // another tricky example
    // build-1: inputA --> outputA
    // build-2: inputA unchanged before the build, inputB --> inputA
    // now inputA is both input and output, which is not supported by this context

    // multi-pass implementation
    // pass 1, carry-over up-to-date inputs and collect all up-to-date outputs
    // pass 2, carry-over all up-to-date outputs
    // pass 3, remove obsolete and orphaned outputs

    Set<File> uptodateOldOutputs = new HashSet<>();
    for (Object resource : oldState.resourcesMap().keySet()) {
      if (oldState.isOutput(resource)) {
        continue;
      }

      if (isProcessedResource(resource) || isDeletedResource(resource)
          || !isRegisteredResource(resource)) {
        // deleted or processed resource, nothing to carry over
        continue;
      }

      if (state.isOutput(resource)) {
        // resource flipped from input to output without going through delete
        throw new IllegalStateException("Inconsistent resource type change " + resource);
      }

      // carry over

      state.putResource(resource, oldState.getResource(resource));
      state.setResourceMessages(resource, oldState.getResourceMessages(resource));
      state.setResourceAttributes(resource, oldState.getResourceAttributes(resource));

      Collection<File> oldOutputs = oldState.getResourceOutputs(resource);
      state.setResourceOutputs(resource, oldOutputs);
      if (oldOutputs != null) {
        uptodateOldOutputs.addAll(oldOutputs);
      }
    }

    for (File output : uptodateOldOutputs) {
      if (state.isResource(output)) {
        // can't carry-over registered resources
        throw new IllegalStateException();
      }

      state.putResource(output, oldState.getResource(output));
      state.addOutput(output);
      state.setResourceMessages(output, oldState.getResourceMessages(output));
      state.setResourceAttributes(output, oldState.getResourceAttributes(output));

      if (workspace instanceof Workspace2) {
        ((Workspace2) workspace).carryOverOutput(output);
      }
    }

    for (File output : oldState.getOutputs()) {
      if (!state.isOutput(output)) {
        deleteOutput(output);
      }
    }
  }

  @Override
  public void markSkipExecution() {
    super.markSkipExecution();
  }

  @Override
  public DefaultResourceMetadata<File> registerInput(File inputFile) {
    return super.registerInput(inputFile);
  }

  @Override
  public Collection<DefaultResourceMetadata<File>> registerInputs(File basedir,
      Collection<String> includes, Collection<String> excludes) throws IOException {
    return super.registerInputs(basedir, includes, excludes);
  }

  @Override
  protected void assertAssociation(DefaultResource<?> resource, DefaultOutput output) {
    Object input = resource.getResource();
    File outputFile = output.getResource();

    // input --> output --> output2 is not supported (until somebody provides a usecase)
    if (state.isOutput(input)) {
      throw new UnsupportedOperationException();
    }

    // each output can only be associated with a single input
    Collection<Object> inputs = state.getOutputInputs(outputFile);
    if (inputs != null && !inputs.isEmpty() && !containsOnly(inputs, input)) {
      throw new UnsupportedOperationException();
    }
  }

  private static boolean containsOnly(Collection<Object> collection, Object element) {
    for (Object other : collection) {
      if (!element.equals(other)) {
        return true;
      }
    }
    return true;
  }
}
