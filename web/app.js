"use strict";
const list=document.querySelector("#demo-list"),form=document.querySelector("#demo-form"),input=document.querySelector("#demo-input"),status=document.querySelector("#demo-status");
list.addEventListener("click",e=>{const button=e.target.closest(".check");if(!button)return;const row=button.closest("li");const done=row.classList.toggle("done");button.setAttribute("aria-pressed",String(done));status.textContent=done?"记下这份进展，也给自己一点肯定。":"没关系，可以继续慢慢来。";});
form.addEventListener("submit",e=>{e.preventDefault();const text=input.value.trim();if(!text)return;if(list.children.length>=12){status.textContent="页面体验最多记录12项，请下载应用继续。";return;}const row=document.createElement("li"),button=document.createElement("button"),title=document.createElement("span");button.type="button";button.className="check";button.setAttribute("aria-label",`标记${text}完成`);title.textContent=text;row.append(button,title);list.append(row);input.value="";row.scrollIntoView({block:"nearest"});status.textContent="已记下。页面体验不会上传或保存你的内容。";});

// Download metadata is copied from the signed artifact after release verification.
fetch("downloads/v0.5.2-preview.1/release.json",{cache:"no-store"}).then(r=>{if(!r.ok)throw Error("metadata");return r.json();}).then(m=>{
if(m.version!=="0.5.2-preview.1"||m.package!=="io.whaletodo.app.preview"||!/^[a-f0-9]{64}$/.test(m.sha256)||!Number.isSafeInteger(m.bytes)||m.bytes<=0)throw Error("metadata");
document.querySelector("#apk-sha").textContent=m.sha256;
document.querySelector("#apk-size").textContent=(m.bytes/1024/1024).toFixed(1)+" MB · 固定发行签名";
}).catch(()=>{document.querySelector("#apk-size").textContent="请查看版本与校验文件";});
