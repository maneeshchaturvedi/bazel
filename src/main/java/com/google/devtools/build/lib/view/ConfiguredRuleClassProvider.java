// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.view;

import static com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType.ABSTRACT;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType.TEST;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.graph.Digraph;
import com.google.devtools.build.lib.graph.Node;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.SkylarkRuleFactory;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.rules.SkylarkRuleClassFunctions;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.syntax.ValidationEnvironment;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.view.PrerequisiteMap.Prerequisite;
import com.google.devtools.build.lib.view.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.view.config.BuildOptions;
import com.google.devtools.build.lib.view.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.view.config.DefaultsPackage;
import com.google.devtools.build.lib.view.config.FragmentOptions;
import com.google.devtools.common.options.OptionsClassProvider;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Knows about every rule Blaze supports and the associated configuration options.
 *
 * <p>This class is initialized on server startup and the set of rules, build info factories
 * and configuration options is guarantees not to change over the life time of the Blaze server.
 */
public class ConfiguredRuleClassProvider implements RuleClassProvider {
  /**
   * Custom dependency validation logic.
   */
  public static interface PrerequisiteValidator {
    /**
     * Checks whether the rule in {@code contextBuilder} is allowed to depend on
     * {@code prerequisite} through the attribute {@code attribute}.
     *
     * <p>Can be used for enforcing any organization-specific policies about the layout of the
     * workspace.
     */
    void validate(
        RuleContext.Builder contextBuilder, Prerequisite prerequisite, Attribute attribute);
  }

  /**
   * Builder for {@link ConfiguredRuleClassProvider}.
   */
  public static class Builder implements RuleDefinitionEnvironment {
    private final List<ConfigurationFragmentFactory> configurationFragments = new ArrayList<>();
    private final List<BuildInfoFactory> buildInfoFactories = new ArrayList<>();
    private final List<Class<? extends FragmentOptions>> configurationOptions = new ArrayList<>();

    private final Map<String, RuleClass> ruleClassMap = new HashMap<>();
    private final  Map<String, Class<? extends RuleDefinition>> ruleDefinitionMap =
        new HashMap<>();
    private final Map<RuleClass, RuleConfiguredTargetFactory> configuredClassMap = new HashMap<>();
    private final Map<Class<? extends RuleDefinition>, RuleClass> ruleMap = new HashMap<>();
    private final Digraph<Class<? extends RuleDefinition>> dependencyGraph =
        new Digraph<>();
    private boolean allowConfigurableAttributes;
    private ConfigurationCollectionFactory configurationCollectionFactory;
    private PrerequisiteValidator prerequisiteValidator;
    private ImmutableMap<String, Class<?>> skylarkAccessibleJavaClasses;
    private ValidationEnvironment skylarkValidationEnvironment;

    public Builder setPrerequisiteValidator(PrerequisiteValidator prerequisiteValidator) {
      this.prerequisiteValidator = prerequisiteValidator;
      return this;
    }

    public Builder addBuildInfoFactory(BuildInfoFactory factory) {
      buildInfoFactories.add(factory);
      return this;
    }

    public Builder addRuleDefinition(Class<? extends RuleDefinition> ruleDefinition) {
      dependencyGraph.createNode(ruleDefinition);
      BlazeRule annotation = ruleDefinition.getAnnotation(BlazeRule.class);
      for (Class<? extends RuleDefinition> ancestor : annotation.ancestors()) {
        dependencyGraph.addEdge(ancestor, ruleDefinition);
      }

      return this;
    }

    public Builder addConfigurationOptions(Class<? extends FragmentOptions> configurationOptions) {
      this.configurationOptions.add(configurationOptions);
      return this;
    }

    public Builder addConfigurationFragment(ConfigurationFragmentFactory factory) {
      configurationFragments.add(factory);
      return this;
    }

    public Builder allowConfigurableAttributes(boolean allow) {
      this.allowConfigurableAttributes = allow;
      return this;
    }

    public Builder setConfigurationCollectionFactory(ConfigurationCollectionFactory factory) {
      this.configurationCollectionFactory = factory;
      return this;
    }

    public Builder setSkylarkAccessibleJavaClasses(ImmutableMap<String, Class<?>> objects) {
      this.skylarkAccessibleJavaClasses = objects;
      return this;
    }

    public Builder setSkylarkValidationEnvironment(ValidationEnvironment env) {
      this.skylarkValidationEnvironment = env;
      return this;
    }

    private RuleConfiguredTargetFactory createFactory(
        Class<? extends RuleConfiguredTargetFactory> factoryClass) {
      try {
        Constructor<? extends RuleConfiguredTargetFactory> ctor = factoryClass.getConstructor();
        return ctor.newInstance();
      } catch (NoSuchMethodException | IllegalAccessException | InstantiationException
          | InvocationTargetException e) {
        throw new IllegalStateException(e);
      }
    }

    private RuleClass commitRuleDefinition(Class<? extends RuleDefinition> definitionClass) {
      BlazeRule annotation = definitionClass.getAnnotation(BlazeRule.class);
      Preconditions.checkArgument(ruleClassMap.get(annotation.name()) == null, annotation.name());

      Preconditions.checkArgument(
          annotation.type() == ABSTRACT ^
          annotation.factoryClass() != RuleConfiguredTargetFactory.class);
      Preconditions.checkArgument(
          (annotation.type() != TEST) ||
          Arrays.asList(annotation.ancestors()).contains(
              BaseRuleClasses.TestBaseRule.class));

      RuleDefinition instance;
      try {
        instance = definitionClass.newInstance();
      } catch (IllegalAccessException | InstantiationException e) {
        throw new IllegalStateException(e);
      }
      RuleClass[] ancestorClasses = new RuleClass[annotation.ancestors().length];
      for (int i = 0; i < annotation.ancestors().length; i++) {
        ancestorClasses[i] = ruleMap.get(annotation.ancestors()[i]);
        if (ancestorClasses[i] == null) {
          // Ancestors should have been initialized by now
          throw new IllegalStateException("Ancestor " + annotation.ancestors()[i] + " of "
              + annotation.name() + " is not initialized");
        }
      }

      RuleClass.Builder builder = new RuleClass.Builder(
          annotation.name(), annotation.type(), false, ancestorClasses);
      builder.allowConfigurableAttributes(allowConfigurableAttributes);
      RuleClass ruleClass = instance.build(builder, this);
      ruleMap.put(definitionClass, ruleClass);
      ruleClassMap.put(ruleClass.getName(), ruleClass);
      ruleDefinitionMap.put(ruleClass.getName(), definitionClass);

      RuleConfiguredTargetFactory factory;
      if (annotation.type() != ABSTRACT) {
        factory = createFactory(annotation.factoryClass());
        configuredClassMap.put(ruleClass, factory);
      }

      return ruleClass;
    }

    public ConfiguredRuleClassProvider build() {
      for (Node<Class<? extends RuleDefinition>> ruleDefinition :
        dependencyGraph.getTopologicalOrder()) {
        commitRuleDefinition(ruleDefinition.getLabel());
      }

      return new ConfiguredRuleClassProvider(
          ImmutableMap.copyOf(ruleClassMap),
          ImmutableMap.copyOf(configuredClassMap),
          ImmutableMap.copyOf(ruleDefinitionMap),
          ImmutableList.copyOf(buildInfoFactories),
          ImmutableList.copyOf(configurationOptions),
          ImmutableList.copyOf(configurationFragments),
          configurationCollectionFactory,
          prerequisiteValidator,
          skylarkAccessibleJavaClasses,
          skylarkValidationEnvironment);
    }

    @Override
    public Label getLabel(String labelValue) {
      return LABELS.getUnchecked(labelValue);
    }
  }

  /**
   * Used to make the label instances unique, so that we don't create a new
   * instance for every rule.
   */
  private static LoadingCache<String, Label> LABELS = CacheBuilder.newBuilder().build(
      new CacheLoader<String, Label>() {
    @Override
    public Label load(String from) {
      try {
        return Label.parseAbsolute(from);
      } catch (Label.SyntaxException e) {
        throw new IllegalArgumentException(from);
      }
    }
  });

  /**
   * Maps rule class name to the metaclass instance for that rule.
   */
  private final ImmutableMap<String, RuleClass> ruleClassMap;

  /**
   * Maps rule class name to the rule definition metaclasses.
   */
  private final ImmutableMap<String, Class<? extends RuleDefinition>> ruleDefinitionMap;

  /**
   * Maps rule class objects to the corresponding configured classes.
   */
  private final ImmutableMap<RuleClass, RuleConfiguredTargetFactory> configuredClassMap;

  /**
   * The configuration options that affect the behavior of the rules.
   */
  private final ImmutableList<Class<? extends FragmentOptions>> configurationOptions;

  /**
   * The set of configuration fragment factories.
   */
  private final ImmutableList<ConfigurationFragmentFactory> configurationFragments;

  /**
   * The factory that creates the configuration collection.
   */
  private final ConfigurationCollectionFactory configurationCollectionFactory;

  private final ImmutableList<BuildInfoFactory> buildInfoFactories;

  private final PrerequisiteValidator prerequisiteValidator;

  private final ImmutableMap<String, Class<?>> skylarkAccessibleJavaClasses;

  private final ValidationEnvironment skylarkValidationEnvironment;

  public ConfiguredRuleClassProvider(
      ImmutableMap<String, RuleClass> ruleClassMap,
      ImmutableMap<RuleClass, RuleConfiguredTargetFactory> configuredClassMap,
      ImmutableMap<String, Class<? extends RuleDefinition>> ruleDefinitionMap,
      ImmutableList<BuildInfoFactory> buildInfoFactories,
      ImmutableList<Class<? extends FragmentOptions>> configurationOptions,
      ImmutableList<ConfigurationFragmentFactory> configurationFragments,
      ConfigurationCollectionFactory configurationCollectionFactory,
      PrerequisiteValidator prerequisiteValidator,
      ImmutableMap<String, Class<?>> skylarkAccessibleJavaClasses,
      ValidationEnvironment skylarkValidationEnvironment) {

    this.ruleClassMap = ruleClassMap;
    this.configuredClassMap = configuredClassMap;
    this.ruleDefinitionMap = ruleDefinitionMap;
    this.buildInfoFactories = buildInfoFactories;
    this.configurationOptions = configurationOptions;
    this.configurationFragments = configurationFragments;
    this.configurationCollectionFactory = configurationCollectionFactory;
    this.prerequisiteValidator = prerequisiteValidator;
    this.skylarkAccessibleJavaClasses = skylarkAccessibleJavaClasses;
    this.skylarkValidationEnvironment = skylarkValidationEnvironment;
  }

  public PrerequisiteValidator getPrerequisiteValidator() {
    return prerequisiteValidator;
  }

  @Override
  public Map<String, RuleClass> getRuleClassMap() {
    return ruleClassMap;
  }

  /**
   * Creates a configured target object for the specified rule and initializes it if necessary.
   *
   * @param rule the rule for which to instantiate the configured target class
   * @param ruleContext information about the transitive closure of the rule that is needed for
   *     constructing the configured target class
   * @return the configured target instance
   * @throws IllegalStateException if the configured class for the specified rule cannot be found
   */
  public RuleConfiguredTarget createConfiguredTarget(Rule rule, RuleContext ruleContext)
      throws InterruptedException {
    RuleConfiguredTargetFactory factory = configuredClassMap.get(rule.getRuleClassObject());
    Preconditions.checkArgument(factory != null, rule.getRuleClassObject());
    return factory.create(ruleContext);
  }

  /**
   * Returns a list of build info factories that are needed for the supported languages.
   */
  public ImmutableList<BuildInfoFactory> getBuildInfoFactories() {
    return buildInfoFactories;
  }

  /**
   * Returns the set of configuration fragments provided by this module.
   */
  public ImmutableList<ConfigurationFragmentFactory> getConfigurationFragments() {
    return configurationFragments;
  }

  /**
   * Returns the set of configuration options that are supported in this module.
   */
  public ImmutableList<Class<? extends FragmentOptions>> getConfigurationOptions() {
    return configurationOptions;
  }

  /**
   * Returns the definition of the rule class definition with the specified name.
   */
  public Class<? extends RuleDefinition> getRuleClassDefinition(String ruleClassName) {
    return ruleDefinitionMap.get(ruleClassName);
  }

  /**
   * Returns the configuration collection creator.
   */
  public ConfigurationCollectionFactory getConfigurationCollectionFactory() {
    return configurationCollectionFactory;
  }

  /**
   * Returns the defaults package for the default settings.
   */
  public String getDefaultsPackageContent() {
    return DefaultsPackage.getDefaultsPackageContent(configurationOptions);
  }

  /**
   * Returns the defaults package for the given options taken from an optionsProvider.
   */
  public String getDefaultsPackageContent(OptionsClassProvider optionsProvider) {
    return DefaultsPackage.getDefaultsPackageContent(
        BuildOptions.of(configurationOptions, optionsProvider));
  }

  /**
   * Creates a BuildOptions class for the given options taken from an optionsProvider.
   */
  public BuildOptions createBuildOptions(OptionsClassProvider optionsProvider) {
    return BuildOptions.of(configurationOptions, optionsProvider);
  }

  @Override
  public Environment getSkylarkRuleClassEnvironment(SkylarkRuleFactory ruleFactory, Path file) {
    return SkylarkRuleClassFunctions.getNewEnvironment(ruleFactory, file);
  }

  @Override
  public ImmutableMap<String, Class<?>> getSkylarkAccessibleJavaClasses() {
    return skylarkAccessibleJavaClasses;
  }

  @Override
  public ValidationEnvironment getSkylarkValidationEnvironment() {
    return skylarkValidationEnvironment;
  }
}