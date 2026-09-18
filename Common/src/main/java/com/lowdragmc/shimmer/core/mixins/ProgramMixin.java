package com.lowdragmc.shimmer.core.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.lowdragmc.shimmer.ShimmerConstants;
import com.lowdragmc.shimmer.client.shader.ShaderInjection;
import com.lowdragmc.shimmer.core.IGlslProcessor;
import com.lowdragmc.shimmer.platform.Services;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author KilaBash
 * @date 2022/05/02
 * @implNote ProgramMixin, inject custom shader to vanilla shaders
 */
@Mixin(Program.class)
public abstract class ProgramMixin {

    @Shadow
    @Final
    private Program.Type type;
    private static final Pattern REGEX_VERSION = Pattern.compile("(#(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*version(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*(\\d+))\\b");

    @SuppressWarnings("mapping")
    @ModifyExpressionValue(method = "compileShaderInternal", at = @At(value = "INVOKE"
            , target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/InputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;"))
    private static String transformShader(String shader,Program.Type type, String shaderName, InputStream pShaderDataSame, String pShaderSourceName, GlslPreprocessor processor){

        if (Services.PLATFORM.isEnableInsetShaderInfo()){
            Matcher matcher = REGEX_VERSION.matcher(shader);
            /**
             * 因为mixin插入位置导致mui的shader出现问题
             * 只有开发环境会复现这个问题
             * 旧代码用的是int index = matcher.find() ? matcher.group().length() : 0
             * group().length()是匹配串长度不是它在文件里的位置，只有 #version 从文件第0个字符开始时，这两个数字才碰巧相等
             * 原版shader是文件开头#version所以看不出问题
             * MUI的shader前面有一段版权注释而#version在注释后面
             **/
            int index = matcher.find() ? matcher.end() : 0;
            if (pShaderSourceName.equals("Mod Resources") || pShaderSourceName.equals("Default")){
                shader = new StringBuilder(shader).insert(index,"\n#line __LINE__ //shaderName:" + shaderName).toString();
            }else {
                shader = new StringBuilder(shader).insert(index,"\n#line __LINE__ //ShaderSourceName:" + pShaderSourceName).toString();
            }
        }

        boolean isVsh = type == Program.Type.VERTEX;
        String injectedShader;
        if (isVsh && ShaderInjection.hasInjectVSH(shaderName)) {
            injectedShader = ShaderInjection.injectVSH(shaderName, shader);
        } else if (type == Program.Type.FRAGMENT && ShaderInjection.hasInjectFSH(shaderName)) {
            injectedShader = ShaderInjection.injectFSH(shaderName, shader);
        } else {
            return shader;
        }

        int testShaderId = GlStateManager.glCreateShader(type == Program.Type.VERTEX ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
        GlStateManager.glShaderSource(testShaderId, processor.process(injectedShader));
        GlStateManager.glCompileShader(testShaderId);
        if (GlStateManager.glGetShaderi(testShaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            GlStateManager.glDeleteShader(testShaderId);
            String errorInfo = StringUtils.trim(GlStateManager.glGetShaderInfoLog(testShaderId, Short.MAX_VALUE));
            ShimmerConstants.LOGGER.error("Couldn't compile {} program({},{}):{}", type.name(), pShaderSourceName, shaderName, errorInfo);
            return shader;
        }

        if (processor instanceof IGlslProcessor iGlslProcessor){
            iGlslProcessor.shimmer$clearImportedPathRecord();
        }

        GlStateManager.glDeleteShader(testShaderId);
        return injectedShader;
    }

}
