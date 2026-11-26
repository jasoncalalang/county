// The one optional script on the order page (interface design 7.1), under 2 KB. Nothing depends
// on it: without it the total is absent, the browser submits as usual and cancel asks on a page.
export const ORDER_PAGE_SCRIPT = `(function(){
var d=document;d.body.className+=" js";
var f=d.getElementById("order"),t=d.getElementById("total");
if(f&&t){
var fmt=function(c){return "Total \\u20b1"+(c/100).toLocaleString("en-PH",{minimumFractionDigits:2,maximumFractionDigits:2})};
var sum=function(){var s=0;f.querySelectorAll("input[data-price]").forEach(function(i){var q=parseInt(i.value,10);if(q>0&&q<=9999)s+=q*Math.round(parseFloat(i.getAttribute("data-price"))*100)});t.textContent=fmt(s)};
t.hidden=false;sum();f.addEventListener("input",sum);
var busy=false;f.addEventListener("submit",function(e){if(busy)e.preventDefault();busy=true});
window.addEventListener("pageshow",function(){busy=false});
}
d.querySelectorAll("form[data-confirm]").forEach(function(c){c.addEventListener("submit",function(e){
if(!window.confirm(c.getAttribute("data-confirm"))){e.preventDefault();return}
var h=d.createElement("input");h.type="hidden";h.name="confirmed";h.value="yes";c.appendChild(h)})});
})();`;
