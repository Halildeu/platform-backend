<#-- Based on Keycloak default login.ftl; simplified for corporate look -->
<!DOCTYPE html>
<html lang="${locale.currentLanguageTag}">
<head>
    <meta charset="UTF-8">
    <title>${msg("loginTitle","Kurumsal Giriş")}</title>
    <link rel="stylesheet" type="text/css" href="${url.resourcesPath}/css/style.css">
</head>
<body>
<div class="kc-wrapper">
    <header class="kc-header">
        <div class="kc-logo">LOGO</div>
        <div class="kc-header-title">${msg("loginTitle","Kurumsal Giriş")}</div>
    </header>

    <main class="kc-main">
        <div class="kc-card">
            <h1 class="kc-card-title">${msg("loginTitle","Kurumsal Giriş")}</h1>
            <p class="kc-card-subtitle">${msg("loginSubtitle","Kurumsal hesabınızla giriş yapın.")}</p>

            <#if realm.password>
                <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
                    <input type="hidden" name="credentialId" value="">

                    <#if realm.rememberMe && !usernameEditDisabled??>
                        <input id="rememberMe" name="rememberMe" type="hidden" value="on">
                    </#if>

                    <div class="kc-form-group">
                        <label for="username">${msg("username")}</label>
                        <input tabindex="1" id="username" name="username" value="${(login.username!'')?html}" type="text" autofocus autocomplete="username">
                    </div>

                    <div class="kc-form-group">
                        <label for="password">${msg("password")}</label>
                        <input tabindex="2" id="password" name="password" type="password" autocomplete="current-password">
                    </div>

                    <div class="kc-actions">
                        <input tabindex="3" class="kc-button" name="login" id="kc-login" type="submit" value="${msg("doLogIn")}">
                    </div>
                </form>
            </#if>

            <#if realm.resetPasswordAllowed>
                <div class="kc-links">
                    <a tabindex="4" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
                </div>
            </#if>

            <#if realm.registrationAllowed>
                <div class="kc-links">
                    <a tabindex="5" href="${url.registrationUrl}">${msg("doRegister")}</a>
                </div>
            </#if>

            <#if error??>
                <div class="kc-error">${error}</div>
            </#if>
        </div>
    </main>
</div>
</body>
</html>
