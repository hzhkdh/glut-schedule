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

    fun detectTimetablePage(): String {
        return """
            (function(){
              var html = document.body.innerText;
              var hasTable = document.querySelectorAll('table').length > 0;
              var hasTd = document.querySelectorAll('td').length > 0;
              var tdCount = document.querySelectorAll('td').length;
              var hasMonday = /星期一|周二|周三|周四|周五/.test(html);
              var hasPeriod = /第\d.*节/.test(html);
              var hasCourse = /课程名称|任课教师|课程序号/.test(html);
              var hasWeek = /\d+周/.test(html);
              var score = 0;
              if (hasMonday && hasPeriod) score += 3;
              if (hasCourse) score += 2;
              if (hasWeek) score += 1;
              if (hasTable && tdCount > 10) score += 2;
              return JSON.stringify({
                score: score,
                isTimetable: score >= 3,
                hasTable: hasTable,
                tdCount: tdCount,
                hasMonday: hasMonday,
                hasPeriod: hasPeriod,
                hasCourse: hasCourse,
                hasWeek: hasWeek
              });
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

    fun clickTimetableMenuItem(): String {
        return """
            (function(){
              var keywords = ['个人课表', '本学期课表', '学生课表', '我的课表'];
              var allItems = document.querySelectorAll('li[role="menuitem"], li.el-menu-item, li.el-submenu, .el-menu-item, [role="treeitem"]');
              var found = null;
              for (var i = 0; i < allItems.length; i++) {
                var text = (allItems[i].innerText || allItems[i].textContent || '').replace(/\s+/g, '');
                for (var k = 0; k < keywords.length; k++) {
                  if (text === keywords[k] || text.indexOf(keywords[k]) >= 0) {
                    found = allItems[i];
                    break;
                  }
                }
                if (found) break;
              }
              if (!found) {
                var allSpans = document.querySelectorAll('span');
                for (var j = 0; j < allSpans.length; j++) {
                  var spanText = (allSpans[j].innerText || '').replace(/\s+/g, '');
                  for (var m = 0; m < keywords.length; m++) {
                    if (spanText === keywords[m] || spanText.indexOf(keywords[m]) >= 0) {
                      found = allSpans[j].closest('li');
                      if (found) {
                        j = allSpans.length;
                        break;
                      }
                      found = allSpans[j];
                      j = allSpans.length;
                      break;
                    }
                  }
                }
              }
              if (!found) return 'not_found:no_element';
              found.scrollIntoView({block: 'center'});
              found.click();
              if (typeof found.__vue__ !== 'undefined' && found.__vue__.${'$'}el) {
                found.__vue__.${'$'}el.click();
              }
              ['mousedown', 'mouseup', 'click'].forEach(function(type){
                found.dispatchEvent(new MouseEvent(type, {bubbles: true, cancelable: true, view: window}));
              });
              return 'clicked:' + (found.innerText || '').replace(/\s+/g, '').slice(0, 30);
            })()
        """.trimIndent()
    }

    fun openTimetablePage(): String {
        return """
            (function(){
              var keywords = ['个人课表', '本学期课表', '学生课表', '我的课表'];
              var allNodes = Array.prototype.slice.call(document.querySelectorAll('li, span, div, a'));

              function textOf(node){ return (node.innerText || node.textContent || '').replace(/\s+/g, ''); }
              function scoreMatch(node, keyword) {
                var t = textOf(node);
                if (t === keyword) return 0;
                if (t.indexOf(keyword) >= 0 && t.length <= keyword.length + 6) return 1;
                if (t.indexOf(keyword) >= 0) return 5;
                return 99;
              }

              for (var k = 0; k < keywords.length; k++) {
                var best = null;
                var bestScore = 99;
                for (var i = 0; i < allNodes.length; i++) {
                  var s = scoreMatch(allNodes[i], keywords[k]);
                  if (s < bestScore) {
                    bestScore = s;
                    best = allNodes[i];
                  }
                }
                if (best && bestScore < 5) {
                  var clickable = best.closest('li, a, button, [role="menuitem"], [role="treeitem"]') || best;
                  clickable.scrollIntoView({block: 'center'});
                  clickable.click();
                  ['mousedown', 'mouseup', 'click'].forEach(function(t){
                    clickable.dispatchEvent(new MouseEvent(t, {bubbles: true, cancelable: true, view: window}));
                  });

                  var href = clickable.getAttribute('href') || clickable.querySelector('a')?.getAttribute('href');
                  if (href && href !== '#' && href.indexOf('javascript:') !== 0) {
                    location.href = href;
                    return 'href:' + href;
                  }
                  return 'clicked:' + textOf(clickable).slice(0, 30);
                }
              }
              return 'not_found';
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

    fun navigateToDirectTimetableUrl(): String {
        return """
            (function(){
              function normalize(url) {
                try { return new URL(url, location.href).href; } catch(e) { return ''; }
              }
              var links = Array.prototype.slice.call(document.querySelectorAll('a[href], iframe[src], frame[src]'));
              for (var i = 0; i < links.length; i++) {
                var raw = links[i].getAttribute('href') || links[i].getAttribute('src') || '';
                var url = normalize(raw);
                if (url.indexOf('showTimetable.do') >= 0 &&
                    url.indexOf('timetableType=STUDENT') >= 0 &&
                    /[?&]id=\d+/.test(url)) {
                  location.href = url;
                  return 'navigating_captured:' + url;
                }
              }
              try {
                var keywords = ['个人课表', '本学期课表', '学生课表', '我的课表'];
                var nodes = Array.prototype.slice.call(document.querySelectorAll('li, span, div, a, button'));
                for (var j = 0; j < nodes.length; j++) {
                  var text = (nodes[j].innerText || nodes[j].textContent || '').replace(/\s+/g, '');
                  for (var k = 0; k < keywords.length; k++) {
                    if (text === keywords[k] || text.indexOf(keywords[k]) >= 0) {
                      var clickable = nodes[j].closest('li, a, button, [role="menuitem"], [role="treeitem"]') || nodes[j];
                      clickable.scrollIntoView({block: 'center'});
                      clickable.click();
                      return 'clicked_menu:' + text.slice(0, 30);
                    }
                  }
                }
                return 'not_found:no_current_user_timetable_link';
              } catch(e) {
                return 'error:' + e.message;
              }
            })()
        """.trimIndent()
    }

    fun waitForTimetableAndExtract(): String {
        return """
            (function(){
              var startTime = Date.now();
              var maxWait = 4000;

              function extract() {
                var tables = document.querySelectorAll('table');
                var result = {found: false, html: '', tableCount: tables.length};

                for (var i = 0; i < tables.length; i++) {
                  var html = tables[i].outerHTML || '';
                  var text = (tables[i].innerText || '');
                  if (text.indexOf('周一') >= 0 || text.indexOf('星期') >= 0 ||
                      text.indexOf('节') >= 0 || text.indexOf('课程') >= 0) {
                    result.found = true;
                    result.html = html;
                    break;
                  }
                }

                if (!result.found) {
                  var bodyText = document.body.innerText || '';
                  if (bodyText.indexOf('周一') >= 0 && bodyText.indexOf('节') >= 0) {
                    result.found = true;
                    result.html = document.body.outerHTML;
                  }
                }

                return JSON.stringify(result);
              }

              var immediate = extract();
              var parsed = JSON.parse(immediate);
              if (parsed.found) return immediate;

              var waited = false;
              return JSON.stringify({found: false, html: '', tableCount: 0,
                note: 'no timetable found after ' + ((Date.now() - startTime) / 1000) + 's'});
            })()
        """.trimIndent()
    }

    fun clickExamMenuItem(): String {
        return """
            (function(){
              var keywords = ['考试安排', '我的考试', '学生考试', '考试查询', '考试信息'];
              var allItems = document.querySelectorAll('li[role="menuitem"], li.el-menu-item, li.el-submenu, .el-menu-item, [role="treeitem"]');
              var found = null;
              for (var i = 0; i < allItems.length; i++) {
                var text = (allItems[i].innerText || allItems[i].textContent || '').replace(/\s+/g, '');
                for (var k = 0; k < keywords.length; k++) {
                  if (text === keywords[k] || text.indexOf(keywords[k]) >= 0) {
                    found = allItems[i];
                    break;
                  }
                }
                if (found) break;
              }
              if (!found) {
                var allSpans = document.querySelectorAll('span');
                for (var j = 0; j < allSpans.length; j++) {
                  var spanText = (allSpans[j].innerText || '').replace(/\s+/g, '');
                  for (var m = 0; m < keywords.length; m++) {
                    if (spanText === keywords[m] || spanText.indexOf(keywords[m]) >= 0) {
                      found = allSpans[j].closest('li');
                      if (found) {
                        j = allSpans.length;
                        break;
                      }
                      found = allSpans[j];
                      j = allSpans.length;
                      break;
                    }
                  }
                }
              }
              if (!found) return 'not_found:no_exam_menu_item';
              found.scrollIntoView({block: 'center'});
              found.click();
              if (typeof found.__vue__ !== 'undefined' && found.__vue__.${'$'}el) {
                found.__vue__.${'$'}el.click();
              }
              ['mousedown', 'mouseup', 'click'].forEach(function(type){
                found.dispatchEvent(new MouseEvent(type, {bubbles: true, cancelable: true, view: window}));
              });
              return 'clicked:' + (found.innerText || '').replace(/\s+/g, '').slice(0, 30);
            })()
        """.trimIndent()
    }

    fun detectExamPage(): String {
        return """
            (function(){
              var html = document.body.innerText || '';
              var hasExam = /考试安排|考试时间|考试地点|座位号/.test(html);
              var hasTable = document.querySelectorAll('table').length > 0;
              var hasTd = document.querySelectorAll('td').length > 0;
              var hasCourse = /课程名称|考试课程|科目/.test(html);
              var hasSeat = /座位号|座位/.test(html);
              var score = 0;
              if (hasExam) score += 3;
              if (hasCourse) score += 2;
              if (hasSeat) score += 2;
              if (hasTable && hasTd > 3) score += 2;
              var hasDate = /考试日期|考试时间|\d{4}[-\/年]\d{1,2}[-\/月]\d{1,2}/.test(html);
              if (hasDate) score += 1;
              return JSON.stringify({
                score: score,
                isExam: score >= 4,
                hasExam: hasExam,
                hasTable: hasTable,
                tdCount: hasTd,
                hasCourse: hasCourse,
                hasSeat: hasSeat,
                hasDate: hasDate
              });
            })()
        """.trimIndent()
    }

    fun fallbackPageHtml(): String {
        return """
            (function(){
              var html = '';
              try {
                html += document.documentElement.outerHTML;
              } catch(e) {
                html += '<!-- top error: ' + e.message + ' -->';
                html += '<html><body>' + (document.body ? document.body.innerText : '') + '</body></html>';
              }
              var frames = document.querySelectorAll('iframe,frame');
              for (var i = 0; i < frames.length; i++) {
                try {
                  if (frames[i].contentDocument) {
                    html += '\n<!-- frame-' + i + ' -->\n';
                    html += frames[i].contentDocument.documentElement.outerHTML;
                  }
                } catch(e) {
                  html += '\n<!-- frame-' + i + ' blocked -->\n';
                }
              }
              return html;
            })()
        """.trimIndent()
    }
}
