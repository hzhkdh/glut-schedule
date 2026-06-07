"""Count real courses in nanning_currcourse.html (excluding teacher links)."""
import re, os
script_dir = os.path.dirname(os.path.abspath(__file__))
html_path = os.path.join(script_dir, 'nanning_currcourse.html')
with open(html_path, 'r', encoding='utf-8') as f:
    html = f.read()

# Match <a class="infolist"> but NOT teacher links
pattern = r'<a\b(?!\s*[^>]*teacherinfo)[^>]*class\s*=\s*["\x27]infolist["\x27][^>]*>\s*([^<]+?)\s*</a>'
matches = re.findall(pattern, html, re.IGNORECASE | re.DOTALL)
print(f'Real course names (excluding teachers): {len(matches)}')
for name in matches:
    print(f'  - {name.strip()}')

print(f'\ntable.none blocks: {len(re.findall(r"class=\"none\"", html))}')
print(f'Estimated real courses with time slots: ~{len(matches)} (some MOOC may have no times)')

