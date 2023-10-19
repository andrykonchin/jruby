/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/

package org.jruby.ir.targets.indy;

import com.headius.invokebinder.Binder;
import com.headius.invokebinder.Signature;
import com.headius.invokebinder.SmartBinder;
import com.headius.invokebinder.SmartHandle;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyBasicObject;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyGlobal;
import org.jruby.RubyHash;
import org.jruby.RubyModule;
import org.jruby.RubyNil;
import org.jruby.anno.JRubyMethod;
import org.jruby.internal.runtime.GlobalVariable;
import org.jruby.internal.runtime.methods.AttrReaderMethod;
import org.jruby.internal.runtime.methods.AttrWriterMethod;
import org.jruby.internal.runtime.methods.CompiledIRMethod;
import org.jruby.internal.runtime.methods.DynamicMethod;
import org.jruby.internal.runtime.methods.HandleMethod;
import org.jruby.internal.runtime.methods.MixedModeIRMethod;
import org.jruby.internal.runtime.methods.NativeCallMethod;
import org.jruby.ir.JIT;
import org.jruby.ir.runtime.IRRuntimeHelpers;
import org.jruby.javasupport.proxy.ReifiedJavaProxy;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.Binding;
import org.jruby.runtime.Block;
import org.jruby.runtime.CallType;
import org.jruby.runtime.CompiledIRBlockBody;
import org.jruby.runtime.DynamicScope;
import org.jruby.runtime.Frame;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.callsite.CacheEntry;
import org.jruby.runtime.callsite.CachingCallSite;
import org.jruby.runtime.callsite.FunctionalCachingCallSite;
import org.jruby.runtime.callsite.MonomorphicCallSite;
import org.jruby.runtime.callsite.VariableCachingCallSite;
import org.jruby.runtime.invokedynamic.GlobalSite;
import org.jruby.runtime.invokedynamic.InvocationLinker;
import org.jruby.runtime.invokedynamic.MathLinker;
import org.jruby.runtime.ivars.FieldVariableAccessor;
import org.jruby.runtime.ivars.VariableAccessor;
import org.jruby.runtime.opto.Invalidator;
import org.jruby.runtime.scope.DynamicScopeGenerator;
import org.jruby.specialized.RubyArraySpecialized;
import org.jruby.util.JavaNameMangler;
import org.jruby.util.cli.Options;
import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.SwitchPoint;

import static java.lang.invoke.MethodHandles.Lookup;
import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static org.jruby.runtime.Helpers.arrayOf;
import static org.jruby.runtime.Helpers.constructObjectArrayHandle;
import static org.jruby.runtime.invokedynamic.InvokeDynamicSupport.findStatic;
import static org.jruby.util.CodegenUtils.p;
import static org.jruby.util.CodegenUtils.sig;

public class Bootstrap {
    public final static String BOOTSTRAP_BARE_SIG = sig(CallSite.class, Lookup.class, String.class, MethodType.class);
    public final static String BOOTSTRAP_LONG_STRING_INT_SIG = sig(CallSite.class, Lookup.class, String.class, MethodType.class, long.class, int.class, String.class, int.class);
    public final static String BOOTSTRAP_DOUBLE_STRING_INT_SIG = sig(CallSite.class, Lookup.class, String.class, MethodType.class, double.class, int.class, String.class, int.class);
    public final static String BOOTSTRAP_INT_INT_SIG = sig(CallSite.class, Lookup.class, String.class, MethodType.class, int.class, int.class);
    public final static String BOOTSTRAP_INT_SIG = sig(CallSite.class, Lookup.class, String.class, MethodType.class, int.class);
    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);
    static final Lookup LOOKUP = MethodHandles.lookup();
    private static final String[] GENERIC_CALL_PERMUTE = {"context", "self", "arg.*"};


    public static Handle isNilBoot() {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                p(Bootstrap.class),
                "isNil",
                sig(CallSite.class, Lookup.class, String.class, MethodType.class),
                false);
    }

    public static CallSite isNil(Lookup lookup, String name, MethodType type) {
        return new IsNilSite();
    }

    public static class IsNilSite extends MutableCallSite {

        public static final MethodType TYPE = methodType(boolean.class, IRubyObject.class);

        private static final MethodHandle INIT_HANDLE =
                Binder.from(TYPE.insertParameterTypes(0, IsNilSite.class)).invokeVirtualQuiet(LOOKUP, "init");

        private static final MethodHandle IS_NIL_HANDLE =
                Binder
                        .from(boolean.class, IRubyObject.class, RubyNil.class)
                        .invokeStaticQuiet(LOOKUP, IsNilSite.class, "isNil");

        public IsNilSite() {
            super(TYPE);

            setTarget(INIT_HANDLE.bindTo(this));
        }

        public boolean init(IRubyObject obj) {
            IRubyObject nil = obj.getRuntime().getNil();

            setTarget(insertArguments(IS_NIL_HANDLE, 1, nil));

            return nil == obj;
        }

        public static boolean isNil(IRubyObject obj, RubyNil nil) {
            return nil == obj;
        }
    }

    public static Handle isTrueBoot() {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                p(Bootstrap.class),
                "isTrue",
                sig(CallSite.class, Lookup.class, String.class, MethodType.class),
                false);
    }

    public static CallSite isTrue(Lookup lookup, String name, MethodType type) {
        return new IsTrueSite();
    }

    public static class IsTrueSite extends MutableCallSite {

        public static final MethodType TYPE = methodType(boolean.class, IRubyObject.class);

        public static final MethodHandle INIT_HANDLE = Binder.from(TYPE.insertParameterTypes(0, IsTrueSite.class)).invokeVirtualQuiet(LOOKUP, "init");

        private static final MethodHandle IS_TRUE_HANDLE =
                Binder
                        .from(boolean.class, IRubyObject.class, RubyNil.class, RubyBoolean.False.class)
                        .invokeStaticQuiet(LOOKUP, IsTrueSite.class, "isTruthy");

        public IsTrueSite() {
            super(TYPE);

            setTarget(INIT_HANDLE.bindTo(this));
        }

        public boolean init(IRubyObject obj) {
            Ruby runtime = obj.getRuntime();

            IRubyObject nil = runtime.getNil();
            IRubyObject fals = runtime.getFalse();

            setTarget(insertArguments(IS_TRUE_HANDLE, 1, nil, fals));

            return nil != obj && fals != obj;
        }

        public static boolean isTruthy(IRubyObject obj, RubyNil nil, RubyBoolean.False fals) {
            return nil != obj && fals != obj;
        }
    }

    public static final Handle CALLSITE = new Handle(
            Opcodes.H_INVOKESTATIC,
            p(Bootstrap.class),
            "callSite",
            sig(CallSite.class, Lookup.class, String.class, MethodType.class, String.class, int.class),
            false);

    public static CallSite callSite(Lookup lookup, String name, MethodType type, String id, int callType) {
        return new ConstantCallSite(constant(CachingCallSite.class, callSite(id, callType)));
    }

    private static CachingCallSite callSite(String id, int callType) {
        switch (CallType.fromOrdinal(callType)) {
            case NORMAL:
                return new MonomorphicCallSite(id);
            case FUNCTIONAL:
                return new FunctionalCachingCallSite(id);
            case VARIABLE:
                return new VariableCachingCallSite(id);
            default:
                throw new RuntimeException("BUG: Unexpected call type " + callType + " in JVM6 invoke logic");
        }
    }

    public static final Handle OPEN_META_CLASS = new Handle(
            Opcodes.H_INVOKESTATIC,
            p(Bootstrap.class),
            "openMetaClass",
            sig(CallSite.class, Lookup.class, String.class, MethodType.class, MethodHandle.class, MethodHandle.class, MethodHandle.class, int.class, int.class, int.class),
            false);

    @JIT
    public static CallSite openMetaClass(Lookup lookup, String name, MethodType type, MethodHandle body, MethodHandle scope, MethodHandle setScope, int line, int dynscopeEliminated, int refinements) {
        try {
            StaticScope staticScope = (StaticScope) scope.invokeExact();
            return new ConstantCallSite(insertArguments(OPEN_META_CLASS_HANDLE, 4, body, staticScope, setScope, line, dynscopeEliminated == 1 ? true : false, refinements == 1 ? true : false));
        } catch (Throwable t) {
            Helpers.throwException(t);
            return null;
        }
    }

    private static final MethodHandle OPEN_META_CLASS_HANDLE =
            Binder
                    .from(DynamicMethod.class, ThreadContext.class, IRubyObject.class, String.class, StaticScope.class, MethodHandle.class, StaticScope.class, MethodHandle.class, int.class, boolean.class, boolean.class)
                    .invokeStaticQuiet(LOOKUP, Bootstrap.class, "openMetaClass");

    @JIT
    public static DynamicMethod openMetaClass(ThreadContext context, IRubyObject object, String descriptor, StaticScope parent, MethodHandle body, StaticScope scope, MethodHandle setScope, int line, boolean dynscopeEliminated, boolean refinements) throws Throwable {
        if (scope == null) {
            scope = Helpers.restoreScope(descriptor, parent);
            setScope.invokeExact(scope);
        }
        return IRRuntimeHelpers.newCompiledMetaClass(context, body, scope, object, line, dynscopeEliminated, refinements);
    }

    public static final Handle CHECK_ARITY_SPECIFIC_ARGS = new Handle(
            Opcodes.H_INVOKESTATIC,
            p(Bootstrap.class),
            "checkAritySpecificArgs",
            sig(CallSite.class, Lookup.class, String.class, MethodType.class, int.class, int.class, int.class, int.class),
            false);

    public static final Handle CHECK_ARITY = new Handle(
            Opcodes.H_INVOKESTATIC,
            p(Bootstrap.class),
            "checkArity",
            sig(CallSite.class, Lookup.class, String.class, MethodType.class, int.class, int.class, int.class, int.class),
            false);

    @JIT
    public static CallSite checkArity(Lookup lookup, String name, MethodType type, int req, int opt, int rest, int keyrest) {
        return new ConstantCallSite(insertArguments(CHECK_ARITY_HANDLE, 5, req, opt, rest == 0 ? false : true, keyrest));
    }

    private static final MethodHandle CHECK_ARITY_HANDLE =
            Binder
                    .from(void.class, ThreadContext.class, StaticScope.class, Object[].class, Object.class, Block.class, int.class, int.class, boolean.class, int.class)
                    .invokeStaticQuiet(LOOKUP, Bootstrap.class, "checkArity");

    @JIT
    public static CallSite checkAritySpecificArgs(Lookup lookup, String name, MethodType type, int req, int opt, int rest, int keyrest) {
        return new ConstantCallSite(insertArguments(CHECK_ARITY_SPECIFIC_ARGS_HANDLE, 4, req, opt, rest == 0 ? false : true, keyrest));
    }

    private static final MethodHandle CHECK_ARITY_SPECIFIC_ARGS_HANDLE =
            Binder
                    .from(void.class, ThreadContext.class, StaticScope.class, Object[].class, Block.class, int.class, int.class, boolean.class, int.class)
                    .invokeStaticQuiet(LOOKUP, Bootstrap.class, "checkAritySpecificArgs");

    @JIT
    public static void checkArity(ThreadContext context, StaticScope scope, Object[] args, Object keywords, Block block, int req, int opt, boolean rest, int keyrest) {
        IRRuntimeHelpers.checkArity(context, scope, args, keywords, req, opt, rest, keyrest, block);
    }

    @JIT
    public static void checkAritySpecificArgs(ThreadContext context, StaticScope scope, Object[] args, Block block, int req, int opt, boolean rest, int keyrest) {
        IRRuntimeHelpers.checkAritySpecificArgs(context, scope, args, req, opt, rest, keyrest, block);
    }

    public static CallSite array(Lookup lookup, String name, MethodType type) {
        MethodHandle handle = Binder
                .from(type)
                .collect(1, IRubyObject[].class)
                .invoke(ARRAY_HANDLE);

        return new ConstantCallSite(handle);
    }

    private static final MethodHandle ARRAY_HANDLE =
            Binder
                    .from(RubyArray.class, ThreadContext.class, IRubyObject[].class)
                    .invokeStaticQuiet(LOOKUP, Bootstrap.class, "array");

    public static CallSite hash(Lookup lookup, String name, MethodType type) {
        MethodHandle handle;

        int parameterCount = type.parameterCount();
        if (parameterCount == 1) {
            handle = Binder
                    .from(lookup, type)
                    .cast(type.changeReturnType(RubyHash.class))
                    .filter(0, RUNTIME_FROM_CONTEXT_HANDLE)
                    .invokeStaticQuiet(lookup, RubyHash.class, "newHash");
        } else if (!type.parameterType(parameterCount - 1).isArray()
                && (parameterCount - 1) / 2 <= Helpers.MAX_SPECIFIC_ARITY_HASH) {
            handle = Binder
                    .from(lookup, type)
                    .cast(type.changeReturnType(RubyHash.class))
                    .filter(0, RUNTIME_FROM_CONTEXT_HANDLE)
                    .invokeStaticQuiet(lookup, Helpers.class, "constructSmallHash");
        } else {
            handle = Binder
                    .from(lookup, type)
                    .collect(1, IRubyObject[].class)
                    .invoke(HASH_HANDLE);
        }

        return new ConstantCallSite(handle);
    }

    private static final MethodHandle HASH_HANDLE =
            Binder
                    .from(RubyHash.class, ThreadContext.class, IRubyObject[].class)
                    .invokeStaticQuiet(LOOKUP, Bootstrap.class, "hash");

    public static CallSite kwargsHash(Lookup lookup, String name, MethodType type) {
        MethodHandle handle = Binder
                .from(lookup, type)
                .collect(2, IRubyObject[].class)
                .invoke(KWARGS_HASH_HANDLE);

        return new ConstantCallSite(handle);
    }

    private static final MethodHandle KWARGS_HASH_HANDLE =
            Binder
                    .from(RubyHash.class, ThreadContext.class, RubyHash.class, IRubyObject[].class)
                    .invokeStaticQuiet(LOOKUP, Bootstrap.class, "kwargsHash");

    public static Handle array() {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                p(Bootstrap.class),
                "array",
                sig(CallSite.class, Lookup.class, String.class, MethodType.class),
                false);
    }

    public static Handle hash() {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                p(Bootstrap.class),
                "hash",
                sig(CallSite.class, Lookup.class, String.class, MethodType.class),
                false);
    }

    public static Handle kwargsHash() {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                p(Bootstrap.class),
                "kwargsHash",
                sig(CallSite.class, Lookup.class, String.class, MethodType.class),
                false);
    }

    public static Handle invokeSuper() {
        return SuperInvokeSite.BOOTSTRAP;
    }

    public static Handle global() {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                p(Bootstrap.class),
                "globalBootstrap",
                sig(CallSite.class, Lookup.class, String.class, MethodType.class, String.class, int.class),
                false);
    }

    public static RubyArray array(ThreadContext context, IRubyObject[] ary) {
        assert ary.length > RubyArraySpecialized.MAX_PACKED_SIZE;
        // Bootstrap.array() only dispatches here if ^^ holds
        return RubyArray.newArrayNoCopy(context.runtime, ary);
    }

    // We use LOOKUP here to have a full-featured MethodHandles.Lookup, avoiding jruby/jruby#7911
    private static final MethodHandle RUNTIME_FROM_CONTEXT_HANDLE =
            Binder
                    .from(LOOKUP, Ruby.class, ThreadContext.class)
                    .getFieldQuiet("runtime");

    public static RubyHash hash(ThreadContext context, IRubyObject[] pairs) {
        Ruby runtime = context.runtime;
        RubyHash hash = new RubyHash(runtime, pairs.length / 2 + 1);
        for (int i = 0; i < pairs.length;) {
            hash.fastASetCheckString(runtime, pairs[i++], pairs[i++]);
        }
        return hash;
    }

    public static RubyHash kwargsHash(ThreadContext context, RubyHash hash, IRubyObject[] pairs) {
        return IRRuntimeHelpers.dupKwargsHashAndPopulateFromArray(context, hash, pairs);
    }

    static MethodHandle buildIndyHandle(InvokeSite site, CacheEntry entry) {
        MethodHandle mh = null;
        Signature siteToDyncall = site.signature.insertArgs(site.argOffset, arrayOf("class", "name"), arrayOf(RubyModule.class, String.class));
        DynamicMethod method = entry.method;

        if (method instanceof HandleMethod) {
            HandleMethod handleMethod = (HandleMethod)method;
            boolean blockGiven = site.signature.lastArgType() == Block.class;

            if (site.arity >= 0) {
                mh = handleMethod.getHandle(site.arity);
                if (mh != null) {
                    if (!blockGiven) mh = insertArguments(mh, mh.type().parameterCount() - 1, Block.NULL_BLOCK);
                    if (!site.functional) mh = dropArguments(mh, 1, IRubyObject.class);
                } else {
                    mh = handleMethod.getHandle(-1);
                    if (!site.functional) mh = dropArguments(mh, 1, IRubyObject.class);
                    if (site.arity == 0) {
                        if (!blockGiven) {
                            mh = insertArguments(mh, mh.type().parameterCount() - 2, IRubyObject.NULL_ARRAY, Block.NULL_BLOCK);
                        } else {
                            mh = insertArguments(mh, mh.type().parameterCount() - 2, (Object)IRubyObject.NULL_ARRAY);
                        }
                    } else {
                        // bundle up varargs
                        if (!blockGiven) mh = insertArguments(mh, mh.type().parameterCount() - 1, Block.NULL_BLOCK);

                        mh = SmartBinder.from(lookup(), siteToDyncall)
                                .collect("args", "arg.*", Helpers.constructObjectArrayHandle(site.arity))
                                .invoke(mh)
                                .handle();
                    }
                }
            } else {
                mh = handleMethod.getHandle(-1);
                if (mh != null) {
                    if (!site.functional) mh = dropArguments(mh, 1, IRubyObject.class);
                    if (!blockGiven) mh = insertArguments(mh, mh.type().parameterCount() - 1, Block.NULL_BLOCK);

                    mh = SmartBinder.from(lookup(), siteToDyncall)
                            .invoke(mh)
                            .handle();
                }
            }

            if (mh != null) {
                mh = insertArguments(mh, site.argOffset, entry.sourceModule, site.name());

                if (Options.INVOKEDYNAMIC_LOG_BINDING.load()) {
                    LOG.info(site.name() + "\tbound directly to handle " + Bootstrap.logMethod(method));
                }
            }
        }

        return mh;
    }

    static MethodHandle buildGenericHandle(InvokeSite site, CacheEntry entry) {
        SmartBinder binder;
        DynamicMethod method = entry.method;

        binder = SmartBinder.from(site.signature);

        binder = permuteForGenericCall(binder, method, GENERIC_CALL_PERMUTE);

        binder = binder
                .insert(2, new String[]{"rubyClass", "name"}, new Class[]{RubyModule.class, String.class}, entry.sourceModule, site.name())
                .insert(0, "method", DynamicMethod.class, method);

        if (site.arity > 3) {
            binder = binder.collect("args", "arg.*", constructObjectArrayHandle(site.arity));
        }

        if (Options.INVOKEDYNAMIC_LOG_BINDING.load()) {
            LOG.info(site.name() + "\tbound indirectly " + method + ", " + Bootstrap.logMethod(method));
        }

        return binder.invokeVirtualQuiet(LOOKUP, "call").handle();
    }

    private static SmartBinder permuteForGenericCall(SmartBinder binder, DynamicMethod method, String... basePermutes) {
        if (methodWantsBlock(method)) {
            binder = binder.permute(arrayOf(basePermutes, "block", String[]::new));
        } else {
            binder = binder.permute(basePermutes);
        }
        return binder;
    }

    private static boolean methodWantsBlock(DynamicMethod method) {
        // only include block if native signature receives block, whatever its arity
        boolean wantsBlock = true;
        if (method instanceof NativeCallMethod) {
            DynamicMethod.NativeCall nativeCall = ((NativeCallMethod) method).getNativeCall();
            // if it is a non-JI native call and does not want block, drop it
            // JI calls may lazily convert blocks to an interface type (jruby/jruby#7246)
            if (nativeCall != null && !nativeCall.isJava()) {
                Class[] nativeSignature = nativeCall.getNativeSignature();

                // no args or last arg not a block, do no pass block
                if (nativeSignature.length == 0 || nativeSignature[nativeSignature.length - 1] != Block.class) {
                    wantsBlock = false;
                }
            }
        }
        return wantsBlock;
    }

    static MethodHandle buildMethodMissingHandle(InvokeSite site, CacheEntry entry, IRubyObject self) {
        SmartBinder binder;
        DynamicMethod method = entry.method;

        if (site.arity >= 0) {
            binder = SmartBinder.from(site.signature);

            binder = permuteForGenericCall(binder, method, GENERIC_CALL_PERMUTE)
                    .insert(2,
                            new String[]{"rubyClass", "name", "argName"}
                            , new Class[]{RubyModule.class, String.class, IRubyObject.class},
                            entry.sourceModule,
                            site.name(),
                            self.getRuntime().newSymbol(site.methodName))
                    .insert(0, "method", DynamicMethod.class, method)
                    .collect("args", "arg.*", Helpers.constructObjectArrayHandle(site.arity + 1));
        } else {
            SmartHandle fold = SmartBinder.from(
                    site.signature
                            .permute("context", "self", "args", "block")
                            .changeReturn(IRubyObject[].class))
                    .permute("args")
                    .insert(0, "argName", IRubyObject.class, self.getRuntime().newSymbol(site.methodName))
                    .invokeStaticQuiet(LOOKUP, Helpers.class, "arrayOf");

            binder = SmartBinder.from(site.signature);

            binder = permuteForGenericCall(binder, method, "context", "self", "args")
                    .fold("args2", fold);
            binder = permuteForGenericCall(binder, method, "context", "self", "args2")
                    .insert(2,
                            new String[]{"rubyClass", "name"}
                            , new Class[]{RubyModule.class, String.class},
                            entry.sourceModule,
                            site.name())
                    .insert(0, "method", DynamicMethod.class, method);
        }

        if (Options.INVOKEDYNAMIC_LOG_BINDING.load()) {
            LOG.info(site.name() + "\tbound to method_missing for " + method + ", " + Bootstrap.logMethod(method));
        }

        return binder.invokeVirtualQuiet(LOOKUP, "call").handle();
    }

    static MethodHandle buildAttrHandle(InvokeSite site, CacheEntry entry, IRubyObject self) {
        DynamicMethod method = entry.method;

        if (method instanceof AttrReaderMethod && site.arity == 0) {
            AttrReaderMethod attrReader = (AttrReaderMethod) method;
            String varName = attrReader.getVariableName();

            // we getVariableAccessorForWrite here so it is eagerly created and we don't cache the DUMMY
            VariableAccessor accessor = self.getType().getVariableAccessorForWrite(varName);

            // Ruby to attr reader
            if (Options.INVOKEDYNAMIC_LOG_BINDING.load()) {
                if (accessor instanceof FieldVariableAccessor) {
                    LOG.info(site.name() + "\tbound as field attr reader " + logMethod(method) + ":" + ((AttrReaderMethod)method).getVariableName());
                } else {
                    LOG.info(site.name() + "\tbound as attr reader " + logMethod(method) + ":" + ((AttrReaderMethod)method).getVariableName());
                }
            }

            return createAttrReaderHandle(site, self, self.getType(), accessor);
        } else if (method instanceof AttrWriterMethod && site.arity == 1) {
            AttrWriterMethod attrReader = (AttrWriterMethod)method;
            String varName = attrReader.getVariableName();

            // we getVariableAccessorForWrite here so it is eagerly created and we don't cache the DUMMY
            VariableAccessor accessor = self.getType().getVariableAccessorForWrite(varName);

            // Ruby to attr reader
            if (Options.INVOKEDYNAMIC_LOG_BINDING.load()) {
                if (accessor instanceof FieldVariableAccessor) {
                    LOG.info(site.name() + "\tbound as field attr writer " + logMethod(method) + ":" + ((AttrWriterMethod) method).getVariableName());
                } else {
                    LOG.info(site.name() + "\tbound as attr writer " + logMethod(method) + ":" + ((AttrWriterMethod) method).getVariableName());
                }
            }

            return createAttrWriterHandle(site, self, self.getType(), accessor);
        }

        return null;
    }

    private static MethodHandle createAttrReaderHandle(InvokeSite site, IRubyObject self, RubyClass cls, VariableAccessor accessor) {
        MethodHandle nativeTarget;

        MethodHandle filter = cls.getClassRuntime().getNullToNilHandle();

        MethodHandle getValue;

        if (accessor instanceof FieldVariableAccessor) {
            MethodHandle getter = ((FieldVariableAccessor)accessor).getGetter();
            getValue = Binder.from(site.type())
                    .drop(0, site.argOffset - 1)
                    .filterReturn(filter)
                    .cast(methodType(Object.class, self.getClass()))
                    .invoke(getter);
        } else {
            getValue = Binder.from(site.type())
                    .drop(0, site.argOffset - 1)
                    .filterReturn(filter)
                    .cast(methodType(Object.class, Object.class))
                    .prepend(accessor)
                    .invokeVirtualQuiet(LOOKUP, "get");
        }

        // NOTE: Must not cache the fully-bound handle in the method, since it's specific to this class

        return getValue;
    }

    public static IRubyObject valueOrNil(IRubyObject value, IRubyObject nil) {
        return value == null ? nil : value;
    }

    private static MethodHandle createAttrWriterHandle(InvokeSite site, IRubyObject self, RubyClass cls, VariableAccessor accessor) {
        MethodHandle nativeTarget;

        MethodHandle filter = Binder
                .from(IRubyObject.class, Object.class)
                .drop(0)
                .constant(cls.getRuntime().getNil());

        MethodHandle setValue;

        if (accessor instanceof FieldVariableAccessor) {
            MethodHandle setter = ((FieldVariableAccessor)accessor).getSetter();
            setValue = Binder.from(site.type())
                    .drop(0, site.argOffset - 1)
                    .filterReturn(filter)
                    .cast(methodType(void.class, self.getClass(), Object.class))
                    .invoke(setter);
        } else {
            setValue = Binder.from(site.type())
                    .drop(0, site.argOffset - 1)
                    .filterReturn(filter)
                    .cast(methodType(void.class, Object.class, Object.class))
                    .prepend(accessor)
                    .invokeVirtualQuiet(LOOKUP, "set");
        }

        return setValue;
    }

    static MethodHandle buildJittedHandle(InvokeSite site, CacheEntry entry, boolean blockGiven) {
        MethodHandle mh = null;
        SmartBinder binder;
        CompiledIRMethod compiledIRMethod = null;
        DynamicMethod method = entry.method;
        RubyModule sourceModule = entry.sourceModule;

        if (method instanceof CompiledIRMethod) {
            compiledIRMethod = (CompiledIRMethod)method;
        } else if (method instanceof MixedModeIRMethod) {
            DynamicMethod actualMethod = ((MixedModeIRMethod)method).getActualMethod();
            if (actualMethod instanceof CompiledIRMethod) {
                compiledIRMethod = (CompiledIRMethod) actualMethod;
            } else {
                if (Options.INVOKEDYNAMIC_LOG_BINDING.load()) {
                    LOG.info(site.name() + "\tfailed direct binding due to unjitted method " + Bootstrap.logMethod(method));
                }
            }
        }

        if (compiledIRMethod != null) {

            // attempt IR direct binding
            // TODO: this will have to expand when we start specializing arities

            binder = SmartBinder.from(site.signature)
                    .permute("context", "self", "arg.*", "block");

            if (site.arity == -1) {
                // already [], nothing to do
                mh = (MethodHandle)compiledIRMethod.getHandle();
            } else if (site.arity == 0) {
                MethodHandle specific;
                if ((specific = compiledIRMethod.getHandleFor(site.arity)) != null) {
                    mh = specific;
                } else {
                    mh = (MethodHandle)compiledIRMethod.getHandle();
                    binder = binder.insert(2, "args", IRubyObject.NULL_ARRAY);
                }
            } else {
                MethodHandle specific;
                if ((specific = compiledIRMethod.getHandleFor(site.arity)) != null) {
                    mh = specific;
                } else {
                    mh = (MethodHandle) compiledIRMethod.getHandle();
                    binder = binder.collect("args", "arg.*", Helpers.constructObjectArrayHandle(site.arity));
                }
            }

            if (!blockGiven) {
                binder = binder.append("block", Block.class, Block.NULL_BLOCK);
            }

            binder = binder
                    .insert(1, "scope", StaticScope.class, compiledIRMethod.getStaticScope())
                    .append("class", RubyModule.class, sourceModule)
                    .append("frameName", String.class, site.name());

            mh = binder.invoke(mh).handle();

            if (Options.INVOKEDYNAMIC_LOG_BINDING.load()) {
                LOG.info(site.name() + "\tbound directly to jitted method " + Bootstrap.logMethod(method));
            }
        }

        return mh;
    }

    static MethodHandle buildNativeHandle(InvokeSite site, CacheEntry entry, boolean blockGiven) {
        MethodHandle mh = null;
        SmartBinder binder = null;
        DynamicMethod method = entry.method;

        if (method instanceof NativeCallMethod && ((NativeCallMethod) method).getNativeCall() != null) {
            NativeCallMethod nativeMethod = (NativeCallMethod)method;
            DynamicMethod.NativeCall nativeCall = nativeMethod.getNativeCall();

            DynamicMethod.NativeCall nc = nativeCall;

            if (nc.isJava()) {
                return JavaBootstrap.createJavaHandle(site, method);
            } else {
                int nativeArgCount = getNativeArgCount(method, nativeCall);

                if (nativeArgCount >= 0) { // native methods only support arity 3
                    if (nativeArgCount == site.arity) {
                        // nothing to do
                        binder = SmartBinder.from(lookup(), site.signature);
                    } else {
                        // arity mismatch...leave null and use DynamicMethod.call below
                        if (Options.INVOKEDYNAMIC_LOG_BINDING.load()) {
                            LOG.info(site.name() + "\tdid not match the primary arity for a native method " + Bootstrap.logMethod(method));
                        }
                    }
                } else {
                    // varargs
                    if (site.arity == -1) {
                        // ok, already passing []
                        binder = SmartBinder.from(lookup(), site.signature);
                    } else if (site.arity == 0) {
                        // no args, insert dummy
                        binder = SmartBinder.from(lookup(), site.signature)
                                .insert(site.argOffset, "args", IRubyObject.NULL_ARRAY);
                    } else {
                        // 1 or more args, collect into []
                        binder = SmartBinder.from(lookup(), site.signature)
                                .collect("args", "arg.*", Helpers.constructObjectArrayHandle(site.arity));
                    }
                }

                if (binder != null) {

                    // clean up non-arguments, ordering, types
                    if (!nc.hasContext()) {
                        binder = binder.drop("context");
                    }

                    if (nc.hasBlock() && !blockGiven) {
                        binder = binder.append("block", Block.NULL_BLOCK);
                    } else if (!nc.hasBlock() && blockGiven) {
                        binder = binder.drop("block");
                    }

                    if (nc.isStatic()) {
                        mh = binder
                                .permute("context", "self", "arg.*", "block") // filter caller
                                .cast(nc.getNativeReturn(), nc.getNativeSignature())
                                .invokeStaticQuiet(LOOKUP, nc.getNativeTarget(), nc.getNativeName())
                                .handle();
                    } else {
                        mh = binder
                                .permute("self", "context", "arg.*", "block") // filter caller, move self
                                .castArg("self", nc.getNativeTarget())
                                .castVirtual(nc.getNativeReturn(), nc.getNativeTarget(), nc.getNativeSignature())
                                .invokeVirtualQuiet(LOOKUP, nc.getNativeName())
                                .handle();
                    }

                    if (Options.INVOKEDYNAMIC_LOG_BINDING.load()) {
                        LOG.info(site.name() + "\tbound directly to JVM method " + Bootstrap.logMethod(method));
                    }

                    JRubyMethod anno = nativeCall.getMethod().getAnnotation(JRubyMethod.class);
                    if (anno != null && anno.frame()) {
                        mh = InvocationLinker.wrapWithFrameOnly(site.signature, entry.sourceModule, site.name(), mh);
                    }
                }
            }
        }

        return mh;
    }

    public static int getNativeArgCount(DynamicMethod method, DynamicMethod.NativeCall nativeCall) {
        // if non-Java, must:
        // * exactly match arities or both are [] boxed
        // * 3 or fewer arguments
        return getArgCount(nativeCall.getNativeSignature(), nativeCall.isStatic());
    }

    private static int getArgCount(Class[] args, boolean isStatic) {
        int length = args.length;
        boolean hasContext = false;
        if (isStatic) {
            if (args.length > 1 && args[0] == ThreadContext.class) {
                length--;
                hasContext = true;
            }

            // remove self object
            assert args.length >= 1;
            length--;

            if (args.length > 1 && args[args.length - 1] == Block.class) {
                length--;
            }

            if (length == 1) {
                if (hasContext && args[2] == IRubyObject[].class) {
                    length = -1;
                } else if (args[1] == IRubyObject[].class) {
                    length = -1;
                }
            }
        } else {
            if (args.length > 0 && args[0] == ThreadContext.class) {
                length--;
                hasContext = true;
            }

            if (args.length > 0 && args[args.length - 1] == Block.class) {
                length--;
            }

            if (length == 1) {
                if (hasContext && args[1] == IRubyObject[].class) {
                    length = -1;
                } else if (args[0] == IRubyObject[].class) {
                    length = -1;
                }
            }
        }
        return length;
    }

    public static boolean testType(RubyClass original, IRubyObject self) {
        // naive test
        return original == RubyBasicObject.getMetaClass(self);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fixnum binding

    public static boolean testModuleMatch(ThreadContext context, IRubyObject arg0, int id) {
        return arg0 instanceof RubyModule && ((RubyModule)arg0).id == id;
    }

    public static Handle getFixnumOperatorHandle() {
        return getBootstrapHandle("fixnumOperatorBootstrap", MathLinker.class, BOOTSTRAP_LONG_STRING_INT_SIG);  
    }

    public static Handle getFloatOperatorHandle() {
        return getBootstrapHandle("floatOperatorBootstrap", MathLinker.class, BOOTSTRAP_DOUBLE_STRING_INT_SIG);
    }

    public static Handle checkpointHandle() {
        return getBootstrapHandle("checkpointBootstrap", BOOTSTRAP_BARE_SIG);
    }

    public static Handle callInfoHandle() {
        return getBootstrapHandle("callInfoBootstrap", BOOTSTRAP_INT_SIG);
    }

    public static Handle coverLineHandle() {
        return getBootstrapHandle("coverLineBootstrap", sig(CallSite.class, Lookup.class, String.class, MethodType.class, String.class, int.class, int.class));
    }

    public static Handle getHeapLocalHandle() {
        return getBootstrapHandle("getHeapLocalBootstrap", BOOTSTRAP_INT_INT_SIG);
    }

    public static Handle getHeapLocalOrNilHandle() {
        return getBootstrapHandle("getHeapLocalOrNilBootstrap", BOOTSTRAP_INT_INT_SIG);
    }

    public static Handle getBootstrapHandle(String name, String sig) {
        return getBootstrapHandle(name, Bootstrap.class, sig);
    }

    public static Handle getBootstrapHandle(String name, Class type, String sig) {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                p(type),
                name,
                sig,
                false);
    }

    public static CallSite checkpointBootstrap(Lookup lookup, String name, MethodType type) throws Throwable {
        MutableCallSite site = new MutableCallSite(type);
        MethodHandle handle = lookup.findStatic(Bootstrap.class, "checkpointFallback", methodType(void.class, MutableCallSite.class, ThreadContext.class));

        handle = handle.bindTo(site);
        site.setTarget(handle);

        return site;
    }

    public static void checkpointFallback(MutableCallSite site, ThreadContext context) throws Throwable {
        Ruby runtime = context.runtime;
        Invalidator invalidator = runtime.getCheckpointInvalidator();

        MethodHandle target = Binder
                .from(void.class, ThreadContext.class)
                .nop();
        MethodHandle fallback = lookup().findStatic(Bootstrap.class, "checkpointFallback", methodType(void.class, MutableCallSite.class, ThreadContext.class));
        fallback = fallback.bindTo(site);

        target = ((SwitchPoint)invalidator.getData()).guardWithTest(target, fallback);

        site.setTarget(target);

        // poll for events once since we've ended up back in fallback
        context.pollThreadEvents();
    }

    public static CallSite callInfoBootstrap(Lookup lookup, String name, MethodType type, int callInfo) throws Throwable {
        MethodHandle handle;
        if (callInfo == 0) {
            handle = lookup.findVirtual(ThreadContext.class, "clearCallInfo", methodType(void.class));
        } else {
            handle = lookup.findStatic(IRRuntimeHelpers.class, "setCallInfo", methodType(void.class, ThreadContext.class, int.class));
            handle = insertArguments(handle, 1, callInfo);
        }

        return new ConstantCallSite(handle);
    }

    public static CallSite coverLineBootstrap(Lookup lookup, String name, MethodType type, String filename, int line, int oneshot) throws Throwable {
        MutableCallSite site = new MutableCallSite(type);
        MethodHandle handle = lookup.findStatic(Bootstrap.class, "coverLineFallback", methodType(void.class, MutableCallSite.class, ThreadContext.class, String.class, int.class, boolean.class));

        handle = handle.bindTo(site);
        handle = insertArguments(handle, 1, filename, line, oneshot != 0);
        site.setTarget(handle);

        return site;
    }

    public static void coverLineFallback(MutableCallSite site, ThreadContext context, String filename, int line, boolean oneshot) throws Throwable {
        IRRuntimeHelpers.updateCoverage(context, filename, line);

        if (oneshot) site.setTarget(Binder.from(void.class, ThreadContext.class).dropAll().nop());
    }

    public static CallSite getHeapLocalBootstrap(Lookup lookup, String name, MethodType type, int depth, int location) throws Throwable {
        // no null checking needed for method bodies
        MethodHandle getter;
        Binder binder = Binder
                .from(type);

        if (depth == 0) {
            if (location < DynamicScopeGenerator.SPECIALIZED_GETS.size()) {
                getter = binder.invokeVirtualQuiet(LOOKUP, DynamicScopeGenerator.SPECIALIZED_GETS.get(location));
            } else {
                getter = binder
                        .insert(1, location)
                        .invokeVirtualQuiet(LOOKUP, "getValueDepthZero");
            }
        } else {
            getter = binder
                    .insert(1, arrayOf(int.class, int.class), location, depth)
                    .invokeVirtualQuiet(LOOKUP, "getValue");
        }

        ConstantCallSite site = new ConstantCallSite(getter);

        return site;
    }

    public static CallSite getHeapLocalOrNilBootstrap(Lookup lookup, String name, MethodType type, int depth, int location) throws Throwable {
        MethodHandle getter;
        Binder binder = Binder
                .from(type)
                .filter(1, LiteralValueBootstrap.contextValue(lookup, "nil", methodType(IRubyObject.class, ThreadContext.class)).dynamicInvoker());

        if (depth == 0) {
            if (location < DynamicScopeGenerator.SPECIALIZED_GETS_OR_NIL.size()) {
                getter = binder.invokeVirtualQuiet(LOOKUP, DynamicScopeGenerator.SPECIALIZED_GETS_OR_NIL.get(location));
            } else {
                getter = binder
                        .insert(1, location)
                        .invokeVirtualQuiet(LOOKUP, "getValueDepthZeroOrNil");
            }
        } else {
            getter = binder
                    .insert(1, arrayOf(int.class, int.class), location, depth)
                    .invokeVirtualQuiet(LOOKUP, "getValueOrNil");
        }

        ConstantCallSite site = new ConstantCallSite(getter);

        return site;
    }

    public static CallSite globalBootstrap(Lookup lookup, String name, MethodType type, String file, int line) throws Throwable {
        String[] names = name.split(":");
        String operation = names[0];
        String varName = JavaNameMangler.demangleMethodName(names[1]);
        GlobalSite site = new GlobalSite(type, varName, file, line);
        MethodHandle handle;

        if (operation.equals("get")) {
            handle = lookup.findStatic(Bootstrap.class, "getGlobalFallback", methodType(IRubyObject.class, GlobalSite.class, ThreadContext.class));
        } else {
            handle = lookup.findStatic(Bootstrap.class, "setGlobalFallback", methodType(void.class, GlobalSite.class, IRubyObject.class, ThreadContext.class));
        }

        handle = handle.bindTo(site);
        site.setTarget(handle);

        return site;
    }

    public static IRubyObject getGlobalFallback(GlobalSite site, ThreadContext context) throws Throwable {
        Ruby runtime = context.runtime;
        GlobalVariable variable = runtime.getGlobalVariables().getVariable(site.name());

        if (site.failures() > Options.INVOKEDYNAMIC_GLOBAL_MAXFAIL.load() ||
                variable.getScope() != GlobalVariable.Scope.GLOBAL ||
                RubyGlobal.UNCACHED_GLOBALS.contains(site.name())) {

            // use uncached logic forever
            if (Options.INVOKEDYNAMIC_LOG_GLOBALS.load()) LOG.info("global " + site.name() + " (" + site.file() + ":" + site.line() + ") uncacheable or rebound > " + Options.INVOKEDYNAMIC_GLOBAL_MAXFAIL.load() + " times, reverting to simple lookup");

            MethodHandle uncached = lookup().findStatic(Bootstrap.class, "getGlobalUncached", methodType(IRubyObject.class, GlobalVariable.class));
            uncached = uncached.bindTo(variable);
            uncached = dropArguments(uncached, 0, ThreadContext.class);
            site.setTarget(uncached);
            return (IRubyObject)uncached.invokeWithArguments(context);
        }

        Invalidator invalidator = variable.getInvalidator();
        IRubyObject value = variable.getAccessor().getValue();

        MethodHandle target = constant(IRubyObject.class, value);
        target = dropArguments(target, 0, ThreadContext.class);
        MethodHandle fallback = lookup().findStatic(Bootstrap.class, "getGlobalFallback", methodType(IRubyObject.class, GlobalSite.class, ThreadContext.class));
        fallback = fallback.bindTo(site);

        target = ((SwitchPoint)invalidator.getData()).guardWithTest(target, fallback);

        site.setTarget(target);

//        if (Options.INVOKEDYNAMIC_LOG_GLOBALS.load()) LOG.info("global " + site.name() + " (" + site.file() + ":" + site.line() + ") cached");

        return value;
    }

    public static IRubyObject getGlobalUncached(GlobalVariable variable) throws Throwable {
        return variable.getAccessor().getValue();
    }

    public static void setGlobalFallback(GlobalSite site, IRubyObject value, ThreadContext context) throws Throwable {
        Ruby runtime = context.runtime;
        GlobalVariable variable = runtime.getGlobalVariables().getVariable(site.name());
        MethodHandle uncached = lookup().findStatic(Bootstrap.class, "setGlobalUncached", methodType(void.class, GlobalVariable.class, IRubyObject.class));
        uncached = uncached.bindTo(variable);
        uncached = dropArguments(uncached, 1, ThreadContext.class);
        site.setTarget(uncached);
        uncached.invokeWithArguments(value, context);
    }

    public static void setGlobalUncached(GlobalVariable variable, IRubyObject value) throws Throwable {
        // FIXME: duplicated logic from GlobalVariables.set
        variable.getAccessor().setValue(value);
        variable.trace(value);
        variable.invalidate();
    }

    public static Handle prepareBlock() {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                p(Bootstrap.class),
                "prepareBlock",
                sig(CallSite.class, Lookup.class, String.class,
                        MethodType.class, MethodHandle.class,  MethodHandle.class, MethodHandle.class, MethodHandle.class, String.class, long.class,
                        String.class, int.class, String.class),
                false);
    }

    public static CallSite prepareBlock(Lookup lookup, String name, MethodType type,
                                        MethodHandle bodyHandle,  MethodHandle scopeHandle, MethodHandle setScopeHandle, MethodHandle parentHandle, String scopeDescriptor, long encodedSignature,
                                        String file, int line, String encodedArgumentDescriptors
                                        ) throws Throwable {
        StaticScope staticScope = (StaticScope) scopeHandle.invokeExact();

        if (staticScope == null) {
            staticScope = Helpers.restoreScope(scopeDescriptor, (StaticScope) parentHandle.invokeExact());
            setScopeHandle.invokeExact(staticScope);
        }

        CompiledIRBlockBody body = new CompiledIRBlockBody(bodyHandle, staticScope, file, line, encodedArgumentDescriptors, encodedSignature);

        Binder binder = Binder.from(type);

        binder = binder.fold(FRAME_SCOPE_BINDING);

        // This optimization can't happen until we can see into the method we're calling to know if it reifies the block
        if (false) {
            /*
            if (needsBinding) {
                if (needsFrame) {
            FullInterpreterContext fic = scope.getExecutionContext();
            if (fic.needsBinding()) {
                if (fic.needsFrame()) {
                    binder = binder.fold(FRAME_SCOPE_BINDING);
                } else {
                    binder = binder.fold(SCOPE_BINDING);
                }
            } else {
                if (needsFrame) {
                    binder = binder.fold(FRAME_BINDING);
                } else {
                    binder = binder.fold(SELF_BINDING);
                }
            }*/
        }

        MethodHandle blockMaker = binder.drop(1, 3)
                .append(body)
                .invoke(CONSTRUCT_BLOCK);

        return new ConstantCallSite(blockMaker);
    }

    public CallSite checkArrayArity(Lookup lookup, String name, MethodType methodType, int required, int opt, int rest) {
        return new ConstantCallSite(MethodHandles.insertArguments(CHECK_ARRAY_ARITY, 2, required, opt, (rest == 0 ? false : true)));
    }

    public static final Handle CHECK_ARRAY_ARITY_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC, p(Helpers.class), "checkArrayArity", sig(CallSite.class, Lookup.class, String.class, MethodType.class, int.class, int.class, int.class), false);

    private static final MethodHandle CHECK_ARRAY_ARITY =
            Binder
                    .from(void.class, ThreadContext.class, RubyArray.class, int.class, int.class, boolean.class)
                    .invokeStaticQuiet(LOOKUP, Helpers.class, "irCheckArgsArrayArity");

    static String logMethod(DynamicMethod method) {
        return "[#" + method.getSerialNumber() + " " + method.getImplementationClass().getMethodLocation() + "]";
    }

    static String logBlock(Block block) {
        return "[" + block.getBody().getFile() + ":" + block.getBody().getLine() + "]";
    }

    private static final Binder BINDING_MAKER_BINDER = Binder.from(Binding.class, ThreadContext.class, IRubyObject.class, DynamicScope.class);

    private static final MethodHandle FRAME_SCOPE_BINDING = BINDING_MAKER_BINDER.invokeStaticQuiet(LOOKUP, IRRuntimeHelpers.class, "newFrameScopeBinding");

    private static final MethodHandle FRAME_BINDING = BINDING_MAKER_BINDER.invokeStaticQuiet(LOOKUP, Bootstrap.class, "frameBinding");
    public static Binding frameBinding(ThreadContext context, IRubyObject self, DynamicScope scope) {
        Frame frame = context.getCurrentFrame().capture();
        return new Binding(self, frame, frame.getVisibility());
    }

    private static final MethodHandle SCOPE_BINDING = BINDING_MAKER_BINDER.invokeStaticQuiet(LOOKUP, Bootstrap.class, "scopeBinding");
    public static Binding scopeBinding(ThreadContext context, IRubyObject self, DynamicScope scope) {
        return new Binding(self, scope);
    }

    private static final MethodHandle SELF_BINDING = BINDING_MAKER_BINDER.invokeStaticQuiet(LOOKUP, Bootstrap.class, "selfBinding");
    public static Binding selfBinding(ThreadContext context, IRubyObject self, DynamicScope scope) {
        return new Binding(self);
    }

    private static final MethodHandle CONSTRUCT_BLOCK = Binder.from(Block.class, Binding.class, CompiledIRBlockBody.class).invokeStaticQuiet(LOOKUP, Bootstrap.class, "constructBlock");
    public static Block constructBlock(Binding binding, CompiledIRBlockBody body) throws Throwable {
        return new Block(body, binding);
    }
}
