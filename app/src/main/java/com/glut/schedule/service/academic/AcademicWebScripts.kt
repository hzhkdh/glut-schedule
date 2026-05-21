package com.glut.schedule.service.academic

object AcademicWebScripts {
    fun currentPageHtml(): String {
        return """
            (function(){
              function docHtml(doc, label) {
                try {
                  return '\n<!-- ' + label + ' -->\n' + doc.documentElement.outerHTML;
                } catch (error) {
                  return '\n<!-- ' + label + ': unreadable -->\n';
                }
              }
              var chunks = ['\n<!-- top -->\n' + document.documentElement.outerHTML];
              var frames = Array.prototype.slice.call(document.querySelectorAll('iframe,frame'));
              frames.forEach(function(frame, index){
                try {
                  if (frame.contentWindow && frame.contentWindow.document) {
                    chunks.push(docHtml(frame.contentWindow.document, 'frame-' + index));
                  }
                } catch (error) {
                  chunks.push('\n<!-- frame-' + index + ': cross-origin -->\n');
                }
              });
              return chunks.join('\n');
            })()
        """.trimIndent()
    }

    fun detectLoginForm(): String {
        return """
            (function(){
              function textOf(doc) {
                try { return ((doc.body && doc.body.innerText) || '').replace(/\s+/g, ''); }
                catch (error) { return ''; }
              }
              function hasLoginForm(doc) {
                var inputs = Array.prototype.slice.call(doc.querySelectorAll('input'));
                var hasPassword = false;
                var hasUserField = false;
                for (var i = 0; i < inputs.length; i++) {
                  var input = inputs[i];
                  var type = (input.getAttribute('type') || '').toLowerCase();
                  var attrs = [
                    input.getAttribute('name') || '',
                    input.getAttribute('id') || '',
                    input.getAttribute('placeholder') || '',
                    input.getAttribute('autocomplete') || ''
                  ].join(' ').toLowerCase();
                  if (type === 'password') hasPassword = true;
                  if (/user|username|account|login|j_username|学号|账号|用户/.test(attrs)) {
                    hasUserField = true;
                  }
                }
                var pageText = textOf(doc);
                var hasLoginText = /账号登录|欢迎登录|请输入用户名|请输入密码|统一身份认证|找回密码/.test(pageText);
                return hasPassword && (hasUserField || hasLoginText);
              }
              if (hasLoginForm(document)) return true;
              var frames = Array.prototype.slice.call(document.querySelectorAll('iframe,frame'));
              for (var j = 0; j < frames.length; j++) {
                try {
                  if (frames[j].contentWindow && frames[j].contentWindow.document &&
                      hasLoginForm(frames[j].contentWindow.document)) {
                    return true;
                  }
                } catch (error) {}
              }
              return false;
            })()
        """.trimIndent()
    }

    fun captureLoginCredentials(): String {
        return """
            (function(){
              if (window.__scheduleAppCredentialHooked) return 'already_hooked';
              window.__scheduleAppCredentialHooked = true;

              function allDocs() {
                var docs = [document];
                var frames = Array.prototype.slice.call(document.querySelectorAll('iframe,frame'));
                frames.forEach(function(frame){
                  try {
                    if (frame.contentWindow && frame.contentWindow.document) {
                      docs.push(frame.contentWindow.document);
                    }
                  } catch (error) {}
                });
                return docs;
              }

              function findFields(doc) {
                var inputs = Array.prototype.slice.call(doc.querySelectorAll('input'));
                var password = inputs.find(function(input){
                  return (input.getAttribute('type') || '').toLowerCase() === 'password' ||
                    /j_password|password|pwd|密码/.test([
                      input.getAttribute('name') || '',
                      input.getAttribute('id') || '',
                      input.getAttribute('placeholder') || ''
                    ].join(' ').toLowerCase());
                });
                if (!password) return null;
                var username = inputs.find(function(input){
                  var type = (input.getAttribute('type') || 'text').toLowerCase();
                  var attrs = [
                    input.getAttribute('name') || '',
                    input.getAttribute('id') || '',
                    input.getAttribute('placeholder') || '',
                    input.getAttribute('autocomplete') || ''
                  ].join(' ').toLowerCase();
                  return input !== password && type !== 'password' &&
                    (/j_username|user|username|account|login|学号|账号|用户/.test(attrs) ||
                      (type === 'text' && (input.value || '').length >= 4));
                });
                return username && password ? { username: username, password: password } : null;
              }

              function saveCredentials() {
                try {
                  var docs = allDocs();
                  for (var i = 0; i < docs.length; i++) {
                    var fields = findFields(docs[i]);
                    if (!fields) continue;
                    var username = (fields.username.value || '').trim();
                    var password = fields.password.value || '';
                    if (username && password && window.AndroidCredentialCapture) {
                      window.AndroidCredentialCapture.saveCredentials(username, password);
                      return true;
                    }
                  }
                } catch (error) {}
                return false;
              }

              allDocs().forEach(function(doc){
                Array.prototype.slice.call(doc.querySelectorAll('form')).forEach(function(form){
                  form.addEventListener('submit', saveCredentials, true);
                });
                doc.addEventListener('click', function(event){
                  var text = ((event.target && (event.target.innerText || event.target.value)) || '').replace(/\s+/g, '');
                  var type = ((event.target && event.target.getAttribute && event.target.getAttribute('type')) || '').toLowerCase();
                  if (type === 'submit' || /登录|登陆|SignIn/i.test(text)) saveCredentials();
                }, true);
                doc.addEventListener('keydown', function(event){
                  if (event.key === 'Enter') saveCredentials();
                }, true);
              });

              return 'hooked_ok';
            })()
        """.trimIndent()
    }

    fun interceptApiResponses(): String {
        return """
            (function(){
              if (window.__scheduleAppHooked) return 'already_hooked';
              window.__scheduleAppHooked = true;
              window.__scheduleAppResponses = [];

              var origOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url) {
                this.__url = url;
                this.__method = method;
                return origOpen.apply(this, arguments);
              };

              var origSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function(body) {
                var xhr = this;
                var loadHandler = function() {
                  if (xhr.__url && xhr.__url.indexOf('glut.edu.cn') >= 0) {
                    try {
                      var resp = {
                        url: xhr.__url,
                        method: xhr.__method,
                        status: xhr.status,
                        type: xhr.getResponseHeader('content-type') || '',
                        body: xhr.responseText ? xhr.responseText.substring(0, 50000) : ''
                      };
                      window.__scheduleAppResponses.push(resp);
                    } catch(e) {}
                  }
                };
                xhr.addEventListener('load', loadHandler);
                return origSend.apply(this, arguments);
              };

              if (typeof fetch !== 'undefined') {
                var origFetch = window.fetch;
                window.fetch = function(url, options) {
                  return origFetch.apply(this, arguments).then(function(response) {
                    var cloned = response.clone();
                    var urlStr = typeof url === 'string' ? url : (url.url || '');
                    if (urlStr.indexOf('glut.edu.cn') >= 0) {
                      cloned.text().then(function(text) {
                        window.__scheduleAppResponses.push({
                          url: urlStr,
                          method: (options && options.method) || 'GET',
                          status: cloned.status,
                          type: cloned.headers.get('content-type') || '',
                          body: text.substring(0, 50000)
                        });
                      }).catch(function(){});
                    }
                    return response;
                  });
                };
              }

              return 'hooked_ok';
            })()
        """.trimIndent()
    }

    fun getInterceptedResponses(): String {
        return """
            (function(){
              if (!window.__scheduleAppResponses) return '[]';
              var resp = JSON.stringify(window.__scheduleAppResponses);
              window.__scheduleAppResponses = [];
              return resp;
            })()
        """.trimIndent()
    }

}
