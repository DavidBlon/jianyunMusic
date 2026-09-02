from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:4180"
OUT = Path(__file__).parent


def assert_true(value, message):
    if not value:
        raise AssertionError(message)


with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context(viewport={"width": 1536, "height": 960})
    page = context.new_page()
    errors = []
    page.on("console", lambda msg: errors.append(f"console:{msg.type}:{msg.text}") if msg.type == "error" else None)
    page.on("pageerror", lambda exc: errors.append(f"page:{exc}"))
    page.goto(BASE, wait_until="networkidle")

    assert_true(page.locator(".connection-empty strong").inner_text() == "尚未连接在线音乐来源", "未绑定发现页状态错误")
    assert_true(page.locator(".media-card").count() == 0, "未绑定时不应显示推荐数据")

    page.locator('[data-route="my"]').click()
    page.locator('[data-panel="settings"]').click()
    page.locator('[data-panel="source"]').click()
    source_labels = page.locator(".source-option span").all_inner_texts()
    assert_true(source_labels == ["酷狗音乐 v7", "酷我音乐 v7", "QQ音乐 v7", "网易云音乐 v7"], "四音源入口不完整")

    page.evaluate("""
      localStorage.setItem('jianyun.web.state', JSON.stringify({
        dataVersion: 2, theme: 'deep', sourceId: 'linglan.kg', key: '', maskedKey: '',
        current: { id:'demo', remoteId:'demo', source:'linglan.kg', sourceName:'酷狗', title:'南山雪（古风DJ）', artist:'王野川、Sixteen', artwork:'./assets/app-icon.png', duration:162 },
        queue: [], liked: [], recent: [], playlists: [], searchHistory: [],
        returnRoute:'discover', playerLayout:'disc', playerEffect:'none', rhythmArtwork:true,
        componentVisibility:{songInfo:true,artwork:true,progress:true,transport:true,extras:true,favorite:true}
      }))
    """)
    page.goto(f"{BASE}/?route=player", wait_until="networkidle")
    desktop = page.locator(".player-layout").evaluate("el => { const s=getComputedStyle(el); const d=document.querySelector('.disc-stage').getBoundingClientRect(); const c=document.querySelector('.player-detail').getBoundingClientRect(); return {display:s.display, width:innerWidth, discLeft:d.left, discRight:d.right, detailLeft:c.left, fake:document.body.innerText.includes('让音乐回归音乐本身')} }")
    assert_true(desktop["display"] == "grid", "桌面播放页未展开为双栏")
    assert_true(desktop["discRight"] < desktop["detailLeft"] + 40, "桌面唱片与控制区没有形成左右布局")
    assert_true(not desktop["fake"], "播放页仍存在伪文案")
    page.screenshot(path=str(OUT / "desktop-player.png"), full_page=True)

    context.close()
    mobile = browser.new_context(viewport={"width": 430, "height": 932}, is_mobile=True)
    page = mobile.new_page()
    page.goto(f"{BASE}/?route=player", wait_until="networkidle")
    page.evaluate("""
      localStorage.setItem('jianyun.web.state', JSON.stringify({
        dataVersion: 2, theme: 'deep', sourceId: 'linglan.kg', key: '', maskedKey: '',
        current: { id:'demo', remoteId:'demo', source:'linglan.kg', sourceName:'酷狗', title:'南山雪（古风DJ）', artist:'王野川、Sixteen', artwork:'./assets/app-icon.png', duration:162 },
        queue: [], liked: [], recent: [], playlists: [], searchHistory: [],
        returnRoute:'discover', playerLayout:'disc', playerEffect:'none', rhythmArtwork:true,
        componentVisibility:{songInfo:true,artwork:true,progress:true,transport:true,extras:true,favorite:true}
      })); location.reload()
    """)
    page.wait_for_load_state("networkidle")
    assert_true(page.locator(".player-mobile-top").is_visible(), "移动端播放页顶部信息未显示")
    assert_true(page.locator(".lyrics").count() == 0, "移动端仍存在伪歌词区")
    page.screenshot(path=str(OUT / "mobile-player.png"), full_page=True)
    assert_true(not errors, "浏览器出现错误：" + " | ".join(errors))
    mobile.close()
    browser.close()

print("静态未绑定状态、四音源入口、桌面双栏播放页、移动播放页均通过")
