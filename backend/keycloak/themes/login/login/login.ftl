<#-- Serban Corporate Login Theme — Keycloak 26 -->
<!DOCTYPE html>
<html lang="${locale.currentLanguageTag}">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${msg("loginTitle","Serban — Kurumsal Giriş")}</title>
    <link rel="stylesheet" type="text/css" href="${url.resourcesPath}/css/style.css">
</head>
<body>
<div class="kc-wrapper">
    <header class="kc-header">
        <div class="kc-logo">S</div>
        <div class="kc-header-title">SERBAN</div>
    </header>

    <main class="kc-main">
        <div class="kc-card">
            <h1 class="kc-card-title">${msg("loginTitle","Kurumsal Giriş")}</h1>
            <p class="kc-card-subtitle">${msg("loginSubtitle","Kurumsal hesabınızla güvenli oturum açın.")}</p>

            <#if message?has_content && (message.type = 'error' || message.type = 'warning')>
                <div class="kc-error">
                    ${kcSanitize(message.summary)?no_esc}
                </div>
            </#if>

            <#if realm.password>
                <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
                    <input type="hidden" name="credentialId" value="">

                    <#if realm.rememberMe && !usernameEditDisabled??>
                        <input id="rememberMe" name="rememberMe" type="hidden" value="on">
                    </#if>

                    <div class="kc-form-group">
                        <label for="username">E-posta veya Kullanıcı Adı</label>
                        <input tabindex="1" id="username" name="username" value="${(login.username!'')?html}" type="text" autofocus autocomplete="username" placeholder="admin@example.com">
                    </div>

                    <div class="kc-form-group">
                        <label for="password">Şifre</label>
                        <input tabindex="2" id="password" name="password" type="password" autocomplete="current-password" placeholder="••••••••">
                    </div>

                    <div class="kc-actions">
                        <input tabindex="3" class="kc-button" name="login" id="kc-login" type="submit" value="${msg("doLogIn","Giriş Yap")}">
                    </div>
                </form>
            </#if>

            <#if realm.resetPasswordAllowed>
                <div class="kc-links">
                    <a tabindex="4" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword","Şifremi Unuttum")}</a>
                </div>
            </#if>

            <div class="kc-security-info">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
                    <path d="m9 12 2 2 4-4"/>
                </svg>
                <span>OAuth 2.0 + PKCE ile korunan güvenli kimlik doğrulama</span>
            </div>
        </div>
    </main>
</div>
</body>
</html>
