
function sykMap(){
	var map = window.api.data.map;
	var symap = {};	
	symap["xnm"] = map.xnm;
	symap["xqm"] = map.xqm;
	symap["njdm_id"] = map.njdm_id;
	symap["zyh_id"] = map.zyh_id;
	symap["bh_id"] = map.bh_id;
	symap["tjkbzdm"] = map.tjkbzdm;
	symap["tjkbzxsdm"] = map.tjkbzxsdm;
	symap["zxszjjs"] = map.zxszjjs;
	return symap; 
}

function sjkMap(){
	var map = window.api.data.map;
	var sjkmap = {};	
	sjkmap["xnm"] = map.xnm;
	sjkmap["xqm"] = map.xqm;
	sjkmap["njdm_id"] = map.njdm_id;
	sjkmap["zyh_id"] = map.zyh_id;
	sjkmap["bh_id"] = map.bh_id;
	sjkmap["tjkbzdm"] = map.tjkbzdm;
	sjkmap["tjkbzxsdm"] = map.tjkbzxsdm;
	sjkmap["zxszjjs"] = map.zxszjjs;
	sjkmap["kbzl"] = 'bj';
	return sjkmap; 
}

function getGridColModel(){
	var colModelArr= [
		{label:'ID',name:'rownumbers', index: 'rownumbers',hidden:true,key:true,align:'center'},
		{label:$.i18n.get("xn"),name:'xnmc', index: 'xnmc',align:'left',width:'100px'},
    	{label:$.i18n.get("xq"),name:'xqmmc', index: 'xqmmc',align:'left',width:'80px'},
    	{label:$.i18n.get("kch"),name:'kch', index: 'kch',align:'left',width:'120px'},
    	{label:$.i18n.get("kcmc"),name:'kcmc', index: 'kcmc',align:'left',width:'120px'},
    	{label:'校区',name:'xqmc', index: 'xqmc',align:'left',width:'120px'},
    	{label:'教学班',name:'jxbmc', index: 'jxbmc',align:'left',width:'120px'},
    	{label:'教学班组成',name:'jxbzc', index: 'jxbzc',align:'left',width:'120px'},
    	{label:'周学时',name:'zhxs', index: 'zhxs',align:'left',width:'120px',isnumber:true},
    	{label:'教学班人数',name:'jxbrs', index: 'jxbrs',align:'left',width:'120px',isnumber:true},
    	{label:'教学周次',name:'qsjsz', index: 'qsjsz',align:'left',width:'120px'},
    	{label:'最终容量',name:'zzrl', index: 'zzrl',align:'left',width:'120px',isnumber:true},
    	{label:'选课人数',name:'xkrs', index: 'xkrs',align:'left',width:'120px',isnumber:true},
    	{label:'教师',name:'jsxx', index: 'jsxx',align:'left',width:'120px'},
    	{label:'教师职称',name:'jszc', index: 'jszc',align:'left',width:'120px'},
    	{label:'主辅讲',name:'zfj', index: 'zfj',align:'left',width:'120px'},
    	{label:'授课方式',name:'skfsmc', index: 'skfsmc',align:'left',width:'120px'},
    	{label:'考试方式',name:'ksfsmc', index: 'ksfsmc',align:'left',width:'120px'},
    	{label:'考核方式',name:'khfsmc', index: 'khfsmc',align:'left',width:'120px'},
    	{label:'选课备注',name:'xkbz', index: 'xkbz',align:'left',width:'120px'},
    	{label:'在线信息',name:'zxxx', index: 'zxxx',align:'left',width:'120px'},
    	{label:'学分',name:'xf', index: 'xf',align:'left',width:'120px',isnumber:true},
    	{label:'总学时',name:'zxs', index: 'zxs',align:'left',width:'120px',isnumber:true},
    	{label:'课程总学时',name:'kczxs', index: 'kczxs',align:'left',width:'120px',isnumber:true},
    	{label:'课程性质简称',name:'kcxzjc', index: 'kcxzjc',align:'left',width:'120px'},
    	{label:'课程学时组成',name:'kcxszc', index: 'kcxszc',align:'left',width:'120px'},
    	{label:'专业方向',name:'zyfx', index: 'zyfx',align:'left',width:'120px'},
	]; 
	return colModelArr;
}

var TempGrid1 = $.extend({},BaseJqGrid,{  
	resizeHandle:"#ylkbTable",
	postData:sjkMap(),
	rownumbers : false,
	caption:'其他课程',
	pager: "#qtkcpager", //分页工具栏  
	autowidth: true,
	shrinkToFit: false,
	multiselect:false,
    url: _path + '/kbdy/bjkbdy_cxSjkcxx.html?doType=query', //这是Action的请求地址      
    colModel:getGridColModel(),
	loadComplete:function(){
	}
});




var TempGrid = $.extend({},BaseJqGrid,{  
	resizeHandle:"#ylkbTable",
	postData:sykMap(),
	rownumbers : false,
	caption: $.i18n.get("alert3"),
	pager: "#sycjlrpager", //分页工具栏  
	autowidth: true,
	shrinkToFit: false,
	multiselect:false,
    url: _path + '/sykbdy/cdkbdy_cxDgbjxqSypkxx.html?doType=query', //这是Action的请求地址      
    colModel:[
            {label:'ID',name:'rownumbers', index: 'rownumbers',hidden:true,key:true,align:'center'},
            {label:'xmz_id',name:'xmz_id', index: 'xmz_id',hidden:true,align:'center'},
        	{label:$.i18n.get("xn"),name:'xnmc', index: 'xnmc',align:'left',width:'100px'},
        	{label:$.i18n.get("xq"),name:'xqmc', index: 'xqmc',align:'left',width:'80px'},
        	{label:$.i18n.get("kch"),name:'kch', index: 'kch',align:'left',width:'100px'},
			{label:$.i18n.get("kcmc"),name:'kcmc', index: 'kcmc',align:'left',width:'120px'},
			{label:$.i18n.get("syxmmc"),name:'syxmmc', index: 'syxmmc',align:'left',width:'120px'},
	        {label:$.i18n.get("syfzmc"),name:'syfzmc', index: 'syfzmc',align:'left',width:'120px'},
	        {label:$.i18n.get("syfj"),name:'sydd', index: 'sydd',align:'left',width:'120px'},
	        {label:$.i18n.get("syfjdycd"),name:'dycd', index: 'dycd',align:'left',width:'120px'},
	        {label:$.i18n.get("cdxq"),name:'xqmc_cd', index: 'xqmc_cd',align:'left',width:'100px'},
	        {label:$.i18n.get("skjs"),name:'xm', index: 'xm',align:'left',width:'100px'},
			{label:$.i18n.get("sksj"),name:'sksj', index: 'sksj',align:'left',width:'200px'},
			{label:$.i18n.get("jxbmc"),name:'jxbmc', index: 'jxbmc',align:'left',width:'200px'},
			{label:$.i18n.get("jxbzc"),name:'jxbzc', index: 'jxbzc',align:'left',width:'200px'},
			{label:$.i18n.get("fzrs"),name:'syfzrs', index: 'syfzrs',align:'left',width:'80px'},
			{label:$.i18n.get("xslx"),name:'xsmc', index: 'xsmc',align:'left',width:'80px'},
			{label:$.i18n.get("xs"),name:'zxs', index: 'zxs',align:'left',width:'70px'},
			{label:$.i18n.get("xf"),name:'xf', index: 'xf',align:'left',width:'70px'}
	],
	loadComplete:function(){
		if($("#sycjlrtabGrid").getDataIDs().length==0){
			$("#gbox_sycjlrtabGrid").hide();
			$("#dcsykb").hide();
		}else{
			$("#gbox_sycjlrtabGrid").show();
			$("#dcsykb").show();
		}
	}
});

if($("#tjkbsfxsykb").val()=="1"){	
	$("#sycjlrtabGrid").loadJqGrid(TempGrid);
}

if($("#xxdm").val()=='12753'){
	$("#qtkctabGrid").jqGridWrap(TempGrid1);
}

var TempGrid2 = $.extend({},BaseJqGrid,{  
	resizeHandle:"#ylkbTable",
	postData:sjkMap(),
	rownumbers : false,
	caption:'实习课程',
	pager: "#sxkcpager", //分页工具栏  
	autowidth: true,
	shrinkToFit: false,
	multiselect:false,
	remoteParams:{"zd_fzdm":"N214525-sxkc"},
    url: _path + '/kbdy/xskbdy_cxSxkcxx.html?doType=query', //这是Action的请求地址      
    colModel: [
        {label: '学年', name: 'xnmc', index: 'xnmc', align: 'left', width: '100px'},
        {label: '学期', name: 'xqmc', index: 'xqmc', align: 'left', width: '80px'},
        {label: '课程', name: 'kcmc', index: 'kcmc', align: 'left', width: '180px'},
        {label: '教学班', name: 'jxbmc', index: 'jxbmc', align: 'left', width: '180px'},
        {label: '教师', name: 'xm', index: 'xm', align: 'left', width: '150px'},
        {label: '周次', name: 'zc', index: 'zc', align: 'left', width: '100px'},
        {label: '星期', name: 'xqj', index: 'xqj', align: 'left', width: '100px'},
        {label: '节次', name: 'jc', index: 'jc', align: 'left', width: '100px'},
        {label: '企业名称', name: 'qymc', index: 'qymc', align: 'left', width: '150px'},
        {label: '教学内容', name: 'jxnr', index: 'jxnr', align: 'left', width: '180px'}
    ],
	loadComplete:function(){
	}
});

if($("#xxdm").val()=='12572'){
	$("#sxkctabGrid").jqGridWrap(TempGrid2);
}
//绑定导出操作
$("#dcsykb").click(function(){
	//导出处理编号 规则:JW_模块编号_业务名称 如：JW_N214505_TJKBDY
	var dcclbh = 'JW_N214505_TJKBDY';
	var requestMap = sykMap();
	//用于查询要导出的数据
	var url = _path + '/sykbdy/cdkbdy_cxDcDgbjxqSypkxx.html';			
	//TempGrid必须是全局对象
	$.exportDialog(url,dcclbh,requestMap ,TempGrid.colModel,'#sycjlrtabGrid');
});


function cxKbContent(map, tjkbmc, xszd){
	$("#table1").empty();
	$("#table2").empty();

	var localeKey =  $('#localeKey').val();

	$.ajax({
		type: 'POST', 
		url:_path+'/kbdy/bjkbdy_cxBjKb.html',
		async: false,
		data: map, 
		success: function(data){
			if(data==null) return false;
			var xsbjList = data.xsbjList;
			var xsbjHtml = "";
			if($.founded(xsbjList)){
				$.each(xsbjList,function(index,item){
					xsbjHtml+= item.xslxbj+"-"+(localeKey=='en_US'?item.ywxsmc:item.xsmc);+"&nbsp;"
				})
			}
			if($.founded($("#yf").val())){
				tjkbmc+=" ("+$("#yf").val()+"月 "+")"
			}
			var zs = data.zs; //获取周数
			if(zs!=null && zs!=undefined && zs!=''){
				tjkbmc+=$.i18n.get("di2")+zs+$.i18n.get("zhou2");
		  	}
			var xqbzxxszList = data.xqbzxxszList;
            var bzxxHtml = "";
            if ($.founded(xqbzxxszList) && $("#kbsfxsxqbzxx").val()=='1') {
                $.each(xqbzxxszList, function (index, item) {
                    bzxxHtml += item.bzxx + "(" + item.xqmc + ")<br/>";
                })
            }
            if($.founded(data.kbbzxx) && '99999'==$("#xxdm").val()){
				bzxxHtml = data.kbbzxx||'';
            	bzxxHtml = bzxxHtml.replaceAll('\n','<br/>');
			}
			if('10110'==$("#xxdm").val()){
				if($.founded(data.kbbzxx)){
					bzxxHtml = '带*标记的课程为实验课,'+data.kbbzxx;
				}else{
					bzxxHtml = '带*标记的课程为实验课';
				}
			}
			var qsxqj = data.qsxqj;
			var kblx = data.kblx; //获取课表类型			
			var kbList = data.kbList||[]; //获取课表数据
            var sjkList = data.sjkList||[];//实践课数据
            var jxhjkcList = data.jxhjkcList||[];//教学环节的课程
            var sfxsd = data.sfxsd;
            var djList = "";
			var djdzList = data.djdzList||[];
			var timeList = "";
			var timeListXj = "";
            var diHtml = "";
			if(sfxsd==1){
				diHtml=$.i18n.get("di");
			}
			if(kbList.length==0 && sjkList.length == 0 && jxhjkcList.length == 0){ //如果没有课表数据
				$("#table1").append("<h3 class='align-center'>"+$.i18n.get("wkb")+"</h3>");
				$("#table2").append("<h3 class='align-center'>"+$.i18n.get("wkb")+"</h3>");
				return false;
			}


			var sjkcHtml = "";//实践课
			var qtkcHtml = "";//其他课程
			var jxhjkcHtml="";//教学环节的课程
			var sfhh = ";";
			if($("#xxdm").val()=='13938'){
				sfhh="<br/>";
			}
			if(sjkList.length>0){
				for(var j=0;j<sjkList.length;j++){
					var sjkc = sjkList[j];
					if($("#xxdm").val()=='12835'){
						if(sjkcHtml!=''){
							sjkcHtml+=sfhh+sjkc.sjkcgs;
						}else{
							sjkcHtml+=sjkc.sjkcgs;
						}
					}else{
						if(sjkc.sfsjk==1){
							if(sjkcHtml!=''){
								sjkcHtml +=sfhh+sjkc.sjkcgs;
							}else{
								sjkcHtml +=sjkc.sjkcgs;
							}
						}else{
							if(qtkcHtml!=''){
								qtkcHtml +=sfhh+sjkc.qtkcgs;
							}else{
								qtkcHtml +=sjkc.qtkcgs;
							}
							
						}
					}
				}
			}
			if(jxhjkcList.length>0){
				for(var j=0;j<jxhjkcList.length;j++){
					if(jxhjkcHtml!=''){
						jxhjkcHtml +=";"+jxhjkcList[j].jxhjkcgs;
					}else{
						jxhjkcHtml +=jxhjkcList[j].jxhjkcgs;
					}
				}
			}
			var lxfsHtml="";//联系方式
			var lxfs = data.lxfs;
			var lxfsHtml1 = "";
			if("14122"==$("#xxdm").val()){
				var fdyxm = lxfs.fdyxm;
				var fdylxfs = lxfs.fdylxfs;
				if ($.founded(fdyxm)) {
					lxfsHtml1 += $.i18n.get("fdy2") + fdyxm;
					if ($.founded(fdylxfs)) {
						lxfsHtml1 += '（' + fdylxfs + '）';
					}
				}
			}
			if($.founded(lxfs) && '12829'==$("#xxdm").val()){
				var bzrxm = lxfs.bzrxm;
				var bzrlxfs = lxfs.bzrlxfs;
				var fdyxm = lxfs.fdyxm;
				var fdylxfs = lxfs.fdylxfs;
				var bzxm = lxfs.bzxm;
				var bzlxfs = lxfs.bzlxfs;
				var xwxm = lxfs.xwxm;
				var xwlxfs = lxfs.xwlxfs;
				
				if ($.founded(bzrxm)) {
					lxfsHtml += $.i18n.get("bzr2") + bzrxm;
					if ($.founded(bzrlxfs)) {
						lxfsHtml += '（' + bzrlxfs + '）';
					}
					lxfsHtml += ';';
				}
				if ($.founded(fdyxm)) {
					lxfsHtml += $.i18n.get("fdy2") + fdyxm;
					if ($.founded(fdylxfs)) {
						lxfsHtml += '（' + fdylxfs + '）';
					}
					lxfsHtml += ';';
				}
				if ($.founded(bzxm)) {
					lxfsHtml += $.i18n.get("bz2") + bzxm;
					if ($.founded(bzlxfs)) {
						lxfsHtml += '（' + bzlxfs + '）';
					}
					lxfsHtml += ';';
				}
				if ($.founded(xwxm)) {
					lxfsHtml += $.i18n.get("xw2") + xwxm;
					if ($.founded(xwlxfs)) {
						lxfsHtml += '（' + xwlxfs + '）';
					}
					lxfsHtml += ';';
				}
				
			}
			var preKcmc = ""; // 前一个课程名称
			var preJc = "";
			var preXqj = "";
		 	var kbddsfxsxq =  $('#kbddsfxsxq').val();
		 	var kchspan = "";
			var sjspan =  "";
			var cdspan =  "";
			var jsspan =  "";
			var jxbspan =  "";
			var jxbzcspan =  "";
			var zzrlspan =  "";
			var zyfxspan = "";
			var zyhxkcbjspan ="";
			var kcmcspan = "";
			var kcxzjcspan = "";
			var jxbrsspan = "";
			var kcxzjctbspan = "";
			var jxbrstbspan =  "";
			var xkrsspan =  "";
			var xkrstbspan = "";
			var khfsspan = "";
			var khfstbspan =  "";
			var xkbzspan = "";
			var xkbztbspan =  "";
			var zxxxspan =  "";
			var zxxxtbspan =  "";
			var skfsspan =  "";
			var skfstbspan =  "";
			var kcxszcspan =  "";
			var kcxszctbspan = "";
			var zhxsspan = "";
			var zhxstbspan =  "";
			var zxsspan =  "";
			var zxstbspan =  "";
			var xfspan = "";
			var xftbspan =  "";
			var kczxsspan = "";
			var kczxstbspan =  "";

			var kchtbspan = "";

			var sjtbspan =  "";
			var cdtbspan =  "";
			var jstbspan = "";
			var jxbtbspan = "";
			var jxbzctbspan =  "";
			var zzrlbspan = "";
			var zyfxtbspan = "";
			var zyhxkcbjtbspan = "";		
			var cdlbmcspan = "";
			var ktmcspan = "";
			var cdlbmctbspan =  "";
			var ktmctbspan =  "";
			var zzmmspan = "";
			var zzmmtbspan = "";
			var ksfsspan = "";
			var ksfstbspan = "";
			var skpthyhspan = "";
			var skpthyhtbspan = "";
			var qqqhspan = "";
			var qqqhtbspan = "";
			var kclbmcspan = "";
			var kclbmctbspan = "";
			var jtskwzspan = "";
			var jtskwztbspan = "";
			let djMap = new Map();
			for(var i=0; i<kbList.length; i++){
				var item = kbList[i];
			    var pkbj = item.pkbj;
				var xqj = item.xqj; //星期
				var jcs = item.jcs; //节次
				var xqh_id = item.xqh_id; //校区号
				var xqdm = item.xqdm; //校区归属
				var jxbzc = item.jxbzc;//教学班组成
				if($("#kbylsfxstb").val()=='1'){
					 kchspan = (xszd.kch) ? "<span class='glyphicon glyphicon-leaf'></span> " + $.i18n.get("kch2") + item.kch : "";
					 sjspan = (xszd.sj) ? "<span class='glyphicon glyphicon-calendar'></span> " + $.i18n.get("zs2") + item.zcd : "";
					 cdspan = (xszd.cd) ? ((xszd.xq) ? "<span class='glyphicon glyphicon-tower'></span> " + $.i18n.get("xqu2") + item.xqmc : "") + "<span class='glyphicon glyphicon-map-marker'></span> " + $.i18n.get("skdd2") + item.cdmc : "";
					 jsspan = (xszd.js) ? "<span class='glyphicon glyphicon-user'></span> " + $.i18n.get("js2") + ($.defined(item.xm)?item.xm:"") +((xszd.jgh && $.defined(item.jgh)) ? "/"+item.jgh:"") + ((xszd.jszc && $.defined(item.zcmc)) ? "(" + item.zcmc + ")" : "") + ((xszd.zfj && $.defined(item.zfjmc)) ? "(" + item.zfjmc + ")" : "") : "";
					 zzmmspan = (xszd.zzmm) ? "<span class='glyphicon glyphicon-user'></span> " + $.i18n.get("zzmm") + "：" + ($.defined(item.zzmm)?item.zzmm:" ") : "";
					 jxbspan = (xszd.jxb) ? "<span class='glyphicon glyphicon-home'></span> " + $.i18n.get("jxb2") + ($.defined(item.jxbmc)?item.jxbmc:" ") : "";
					 jxbzcspan = (xszd.jxbzc) ? "<span class='glyphicon glyphicon-home'></span> " + $.i18n.get("jxbzc2") + ($.defined(item.jxbzc)?item.jxbzc:" ")  : "";
					 zzrlspan = (xszd.zzrl) ? "<span class='glyphicon glyphicon-home'></span> " + $.i18n.get("zzrl2") + ($.defined(item.zzrl)?item.zzrl:" ") : "";
					 zyfxspan = (xszd.zyfx && ($.founded(item.zyfx_id) && item.zyfx_id != 'wfx')) ? "<span class='glyphicon glyphicon-screenshot'></span> " + $.i18n.get("zyfx2") + ($.defined(item.zyfxmc)?item.zyfxmc:" ")  : "";
					 zyhxkcbjspan = (xszd.zyhxkcbj) ? "<span class='glyphicon glyphicon-home'></span> " + $.i18n.get("zyhxkcbj2") + ($.defined(item.zyhxkcbj)?item.zyhxkcbj:" ") : "";
					 if (pkbj == -1) {
						 kcmcspan = "<span class='title'>" + item.kcmc + "</span>";
					 } else {
						 kcmcspan = "<span class='title'>" + item.kcmc + (item.xslxbj || '') + "</span>";
					 }
					 kcxzjcspan = (xszd.kcxzjc) ? "<span class='glyphicon glyphicon-list-alt'></span> " + $.i18n.get("kcxzjc2") + ($.defined(item.kcxzjc)?item.kcxzjc:" ") : "";
					 jxbrsspan = (xszd.jxbrs) ? "<span class='glyphicon glyphicon-leaf'></span> " + $.i18n.get("jxbrs2") + ($.defined(item.jxbrs)?item.jxbrs:" ") : "";
					 kcxzjctbspan = (xszd.kcxzjc) ? "<p><span  title=" + $.i18n.get("kcxzjc") + "><span class='glyphicon glyphicon-list-alt'></span></span> " + ($.defined(item.kcxzjc)?item.kcxzjc:" ") + "</p>" : "";
					 jxbrstbspan = (xszd.jxbrs) ? "<p><span  title=" + $.i18n.get("jxbrs") + "><span class='glyphicon glyphicon-leaf'></span></span> " + ($.defined(item.jxbrs)?item.jxbrs:" ") + "</p>" : "";
					 xkrsspan = (xszd.xkrs) ? "<span class='glyphicon glyphicon-leaf'></span> " + $.i18n.get("xkrs2") + ($.defined(item.xkrs)?item.xkrs:" ") : "";
					 xkrstbspan = (xszd.xkrs) ? "<p><span  title=" + $.i18n.get("xkrs") + "><span class='glyphicon glyphicon-leaf'></span></span> " + ($.defined(item.xkrs)?item.xkrs:" ")  + "</p>" : "";
					 khfsspan = (xszd.khfs) ? "<span class='glyphicon glyphicon-check'></span> " + $.i18n.get("khfs2") +($.defined(item.khfsmc)?item.khfsmc:" ") : "";
					 khfstbspan = (xszd.khfs) ? "<p><span  title="+$.i18n.get("khfs")+"><span class='glyphicon glyphicon-check'></span></span> " +  ($.defined(item.khfsmc)?item.khfsmc:" ") + "</p>" : "";
					 ksfsspan = (xszd.ksfs) ? "<span class='glyphicon glyphicon-check'></span> " + $.i18n.get("ksfs2") +($.defined(item.ksfsmc)?item.ksfsmc:" ") : "";
					 ksfstbspan = (xszd.ksfs) ? "<p><span  title="+$.i18n.get("ksfs")+"><span class='glyphicon glyphicon-check'></span></span> " +  ($.defined(item.ksfsmc)?item.ksfsmc:" ") + "</p>" : "";
					 skpthyhspan = (xszd.skpthyh) ? "<span class='glyphicon glyphicon-check'></span> " + $.i18n.get("skpthyh")+"："+($.defined(item.skpthyh)?item.skpthyh:" ") : "";
					 skpthyhtbspan = (xszd.skpthyh) ? "<p><span  title="+$.i18n.get("skpthyh")+"><span class='glyphicon glyphicon-check'></span></span> " +  ($.defined(item.skpthyh)?item.skpthyh:" ") + "</p>" : "";
					 jtskwzspan = (xszd.jtskwz) ? "<span class='glyphicon glyphicon-check'></span> " + $.i18n.get("jtskwz")+"："+($.defined(item.jtskwz)?item.jtskwz:" ") : "";
					 jtskwztbspan = (xszd.jtskwz) ? "<p><span  title="+$.i18n.get("jtskwz")+"><span class='glyphicon glyphicon-check'></span></span> " +  ($.defined(item.jtskwz)?item.jtskwz:" ") + "</p>" : "";
					 qqqhspan = (xszd.qqqh) ? "<span class='glyphicon glyphicon-check'></span> " + $.i18n.get("qqqh")+"："+($.defined(item.qqqh)?item.qqqh:" ") : "";
					 qqqhtbspan = (xszd.qqqh) ? "<p><span  title="+$.i18n.get("qqqh")+"><span class='glyphicon glyphicon-check'></span></span> " +  ($.defined(item.qqqh)?item.qqqh:" ") + "</p>" : "";
					 xkbzspan = (xszd.xkbz) ? "<span class='glyphicon glyphicon-leaf'></span> " + $.i18n.get("xkbz2") + ($.defined(item.xkbz)?item.xkbz:" ") : "";
					 xkbztbspan = (xszd.xkbz) ? "<p><span  title="+$.i18n.get("xkbz")+"><span class='glyphicon glyphicon-leaf'></span></span> " + ($.defined(item.xkbz)?item.xkbz:" ") + "</p>" : "";
					 zxxxspan = (xszd.zxxx) ? "<span class='glyphicon glyphicon-leaf'></span> " + $.i18n.get("zxxx2") + ($.defined(item.zxxx)?item.zxxx:" ") : "";
					 zxxxtbspan = (xszd.zxxx) ? "<p><span  title="+$.i18n.get("zxxx")+"><span class='glyphicon glyphicon-leaf'></span></span> " + ($.defined(item.zxxx)?item.zxxx:" ") + "</p>" : "";
					 skfsspan = (xszd.skfsmc) ? "<span class='glyphicon glyphicon-leaf'></span>  " + $.i18n.get("skfsmc2") + ($.defined(item.skfsmc)?item.skfsmc:" ") : "";
					 skfstbspan = (xszd.skfsmc) ? "<p><span  title="+$.i18n.get("skfsmc")+"><span class='glyphicon glyphicon-leaf'></span></span> " + ($.defined(item.skfsmc)?item.skfsmc:" ") + "</p>" : "";
					 kcxszcspan = (xszd.kcxszc) ? "<span class='glyphicon glyphicon-leaf'></span>  " + $.i18n.get("kcxszc2") + ($.defined(item.kcxszc)?item.kcxszc:" ") : "";
					 kcxszctbspan = (xszd.kcxszc) ? "<p><span  title="+$.i18n.get("kcxszc")+"><span class='glyphicon glyphicon-leaf'></span></span> " +($.defined(item.kcxszc)?item.kcxszc:" ")  + "</p>" : "";
					 zhxsspan = (xszd.zhxs) ? "<span class='glyphicon glyphicon-leaf'></span>  " + $.i18n.get("zhouxs2") + ($.defined(item.zhxs)?item.zhxs:" ") : "";
					 zhxstbspan = (xszd.zhxs) ? "<p><span  title="+$.i18n.get("zhouxs")+"><span class='glyphicon glyphicon-leaf'></span></span> " + ($.defined(item.zhxs)?item.zhxs:" ")  + "</p>" : "";
					 zxsspan = (xszd.zxs) ? "<span class='glyphicon glyphicon-leaf'></span>  " + $.i18n.get("zongxs2") + ($.defined(item.zxs)?item.zxs:" ") : "";
					 zxstbspan = (xszd.zxs) ? "<p><span  title="+$.i18n.get("zongxs")+"><span class='glyphicon glyphicon-leaf'></span></span> " +  ($.defined(item.zxs)?item.zxs:" ")+ "</p>" : "";
					 xfspan = (xszd.xf) ? "<span class='glyphicon glyphicon-leaf'></span>  " + $.i18n.get("xf2") +  ($.defined(item.xf)?item.xf:" ") : "";
					 xftbspan = (xszd.xf) ? "<p><span  title="+$.i18n.get("xf")+"><span class='glyphicon glyphicon-leaf'></span></span> " +  ($.defined(item.xf)?item.xf:" ") + "</p>" : "";
					 kczxsspan = (xszd.kczxs) ? "<span class='glyphicon glyphicon-leaf'></span>  " + $.i18n.get("kczxs2") +  ($.defined(item.kczxs)?item.kczxs:" ") : "";
					 kczxstbspan = (xszd.kczxs) ? "<p><span  title="+$.i18n.get("kczxs")+"><span class='glyphicon glyphicon-leaf'></span></span> " +  ($.defined(item.kczxs)?item.kczxs:" ") + "</p>" : "";

					 kchtbspan = (xszd.kch) ? "<p><span  title="+$.i18n.get("kch")+"><span class='glyphicon glyphicon-leaf'></span></span> " + item.kch + "</p>" : "";
					 if($("#kbylsfxsdj").val() == '2' && $("#xxdm").val()=='10564'){
						if(item.jcma == item.jcmb){
							sjtbspan = (xszd.sj) ? "<p><span  title="+$.i18n.get("j/z")+"><span class='glyphicon glyphicon-time'></span></span> " + item.zcd + "</p>" : "";
						}else{
							sjtbspan = (xszd.sj) ? "<p><span  title="+$.i18n.get("j/z")+"><span class='glyphicon glyphicon-time'></span></span> " + "(" + item.jc + ")" + item.zcd + "</p>" : "";
						}
					 }else{
						 sjtbspan = (xszd.sj) ? "<p><span  title="+$.i18n.get("j/z")+"><span class='glyphicon glyphicon-time'></span></span> " + "(" + item.jc + ")" + item.zcd + "</p>" : "";
					 }
					 
					 cdtbspan = (xszd.cd) ? "<p><span  title="+$.i18n.get("skdd")+"><span class='glyphicon glyphicon-map-marker'></span></span> " + ((xszd.xq) ? item.xqmc : "") + " " + item.cdmc + "</p>" : "";
					 jstbspan = (xszd.js) ? "<p><span  title="+$.i18n.get("js")+"><span class='glyphicon glyphicon-user'></span></span> " + ($.defined(item.xm)?item.xm:"") + ((xszd.jgh && $.defined(item.jgh)) ? "/"+item.jgh:"") + ((xszd.jszc && $.defined(item.zcmc)) ? "(" + item.zcmc + ")" : "") + ((xszd.zfj && $.defined(item.zfjmc)) ? "(" + item.zfjmc + ")" : "") + "</p>" : "";
					 zzmmtbspan = (xszd.zzmm) ? "<p><span  title="+$.i18n.get("zzmm")+"><span class='glyphicon glyphicon-user'></span></span> " + ($.defined(item.zzmm)?item.zzmm:" ")  + "</p>" : "";
					 jxbtbspan = (xszd.jxb) ? "<p><span  title="+$.i18n.get("jxbmc")+"><span class='glyphicon glyphicon-home'></span></span> " +  ($.defined(item.jxbmc)?item.jxbmc:" ") + "</p>" : "";
					 jxbzctbspan = (xszd.jxbzc) ? "<p><span  title="+$.i18n.get("jxbzc")+"><span class='glyphicon glyphicon-home'></span></span> " + ($.defined(item.jxbzc)?item.jxbzc:" ") + "</p>" : "";
					 zzrlbspan = (xszd.zzrl) ? "<p><span  title="+$.i18n.get("zzrl")+"><span class='glyphicon glyphicon-home'></span></span> " + ($.defined(item.zzrl)?item.zzrl:" ") + "</p>" : "";
					 zyfxtbspan = (xszd.zyfx && item.zyfx_id != 'wfx') ? "<p><span  title="+$.i18n.get("zyfx")+"><span class='glyphicon glyphicon-screenshot'></span></span> " +  ($.defined(item.zyfxmc)?item.zyfxmc:" ") + "</p>" : "";
					 zyhxkcbjtbspan = (xszd.zyhxkcbj) ? "<p><span  title="+$.i18n.get("zyhxkcbj")+"><span class='glyphicon glyphicon-home'></span></span> " + ($.defined(item.zyhxkcbj)?item.zyhxkcbj:" ") + "</p>" : "";		
					 cdlbmcspan = (xszd.cdlbmc) ? "<span class='glyphicon glyphicon-leaf'></span>  " + $.i18n.get("cdlbmc") + "：" + ($.defined(item.cdlbmc)?item.cdlbmc:""):"";
					 cdlbmctbspan = (xszd.cdlbmc) ? "<p><span  title="+$.i18n.get("cdlbmc")+"><span class='glyphicon glyphicon-leaf'></span></span> " + ($.defined(item.cdlbmc)?item.cdlbmc:" ") + "</p>" : "";
					 ktmcspan = (xszd.ktmc) ? "<span class='glyphicon glyphicon-leaf'></span>  " + $.i18n.get("ktmc") + "：" + ($.defined(item.ktmc)?item.ktmc:""):"";
					 ktmctbspan = (xszd.ktmc) ? "<p><span  title="+$.i18n.get("ktmc")+"><span class='glyphicon glyphicon-leaf'></span></span> " + ($.defined(item.ktmc)?item.ktmc:" ") + "</p>" : "";
					 kclbmcspan = (xszd.kclbmc) ? "<span class='glyphicon glyphicon-leaf'></span>  " + $.i18n.get("kclbmc") + "：" + ($.defined(item.kclbmc)?item.kclbmc:""):"";
					 kclbmctbspan = (xszd.kclbmc) ? "<p><span  title="+$.i18n.get("kclbmc")+"><span class='glyphicon glyphicon-leaf'></span></span> " + ($.defined(item.kclbmc)?item.kclbmc:" ") + "</p>" : "";
				}else{
					 kchspan = (xszd.kch) ?  $.i18n.get("kch2") + item.kch : "";
					 sjspan = (xszd.sj) ?  $.i18n.get("zs2") + item.zcd : "";
					 cdspan = (xszd.cd) ? ((xszd.xq) ? $.i18n.get("xqu2") + item.xqmc : "") +  $.i18n.get("skdd2") + item.cdmc : "";
					 jsspan = (xszd.js) ? $.i18n.get("js2") + ($.defined(item.xm)?item.xm:"") +((xszd.jgh && $.defined(item.jgh)) ? "/"+item.jgh:"")+ ((xszd.jszc && $.defined(item.zcmc)) ? "(" + item.zcmc + ")" : "") + ((xszd.zfj && $.defined(item.zfjmc)) ? "(" + item.zfjmc + ")" : "") : "";
					 zzmmspan = (xszd.zzmm) ? $.i18n.get("zzmm")+"：" + ($.defined(item.zzmm)?item.zzmm:" ") : "";
					 jxbspan = (xszd.jxb) ?  $.i18n.get("jxb2") + ($.defined(item.jxbmc)?item.jxbmc:" ") : "";
					 jxbzcspan = (xszd.jxbzc) ?  $.i18n.get("jxbzc2") + ($.defined(item.jxbzc)?item.jxbzc:" ") : "";
					 zzrlspan = (xszd.zzrl) ?  $.i18n.get("zzrl2") + ($.defined(item.zzrl)?item.zzrl:" ") : "";
					 zyfxspan = (xszd.zyfx && ($.founded(item.zyfx_id) && item.zyfx_id != 'wfx')) ?  $.i18n.get("zyfx2") +  ($.defined(item.zyfxmc)?item.zyfxmc:" ")  : "";
					 zyhxkcbjspan = (xszd.zyhxkcbj) ? $.i18n.get("zyhxkcbj2") + ($.defined(item.zyhxkcbj)?item.zyhxkcbj:" ")  : "";
					 if (pkbj == -1) {
						 kcmcspan = "<span class='title'>" + item.kcmc + "</span>";
					 } else {
						 kcmcspan = "<span class='title'>" + item.kcmc + (item.xslxbj || '') + "</span>";
					 }
					 kcxzjcspan = (xszd.kcxzjc) ?  $.i18n.get("kcxzjc2") + ($.defined(item.kcxzjc)?item.kcxzjc:" ") : "";
					 jxbrsspan = (xszd.jxbrs) ?  $.i18n.get("jxbrs2") + ($.defined(item.jxbrs)?item.jxbrs:" ") : "";
					 kcxzjctbspan = (xszd.kcxzjc) ? "<p>"+  ($.defined(item.kcxzjc)?item.kcxzjc:" ") + "</p>" : "";
					 jxbrstbspan = (xszd.jxbrs) ? "<p>"+  ($.defined(item.jxbrs)?item.jxbrs:" ") + "</p>" : "";
					 xkrsspan = (xszd.xkrs) ?  $.i18n.get("xkrs2") +  ($.defined(item.xkrs)?item.xkrs:" ")  : "";
					 xkrstbspan = (xszd.xkrs) ? "<p>"+ ($("#xxdm").val()=='10564'?$.i18n.get("xkrs2"):"")+($.defined(item.xkrs)?item.xkrs:" ")  + "</p>" : "";
					 khfsspan = (xszd.khfs) ?  $.i18n.get("khfs2") +($.defined(item.khfsmc)?item.khfsmc:" ")  : "";
					 khfstbspan = (xszd.khfs) ? "<p>"+ ($.defined(item.khfsmc)?item.khfsmc:" ") + "</p>" : "";
					 ksfsspan = (xszd.ksfs) ?  $.i18n.get("ksfs2") +($.defined(item.ksfsmc)?item.ksfsmc:" ")  : "";
					 ksfstbspan = (xszd.ksfs) ? "<p>"+ ($.defined(item.ksfsmc)?item.ksfsmc:" ") + "</p>" : "";
					 skpthyhspan = (xszd.skpthyh) ?  $.i18n.get("skpthyh")+"："+($.defined(item.skpthyh)?item.skpthyh:" ")  : "";
					 skpthyhtbspan = (xszd.skpthyh) ? "<p>"+ ($.defined(item.skpthyh)?item.skpthyh:" ") + "</p>" : "";
					 jtskwzspan = (xszd.jtskwz) ?  $.i18n.get("jtskwz")+"："+($.defined(item.jtskwz)?item.jtskwz:" ")  : "";
					 jtskwztbspan = (xszd.jtskwz) ? "<p>"+ ($.defined(item.jtskwz)?item.jtskwz:" ") + "</p>" : "";
					 qqqhspan = (xszd.qqqh) ?  $.i18n.get("qqqh")+"："+($.defined(item.qqqh)?item.qqqh:" ")  : "";
					 qqqhtbspan = (xszd.qqqh) ? "<p>"+ ($.defined(item.qqqh)?item.qqqh:" ") + "</p>" : "";
					 xkbzspan = (xszd.xkbz) ?  $.i18n.get("xkbz2")+($.defined(item.xkbz)?item.xkbz:" ") : "";
					 xkbztbspan = (xszd.xkbz) ? "<p>"+ ($.defined(item.xkbz)?item.xkbz:" ")  + "</p>" : "";
					 zxxxspan = (xszd.zxxx) ?  $.i18n.get("zxxx2")+($.defined(item.zxxx)?item.zxxx:" ")  : "";
					 zxxxtbspan = (xszd.zxxx) ? "<p>"+ zxxx + "</p>" : "";
					 skfsspan = (xszd.skfsmc) ? $.i18n.get("skfsmc2")+($.defined(item.skfsmc)?item.skfsmc:" ") : "";
					 skfstbspan = (xszd.skfsmc) ? "<p>"+ ($.defined(item.skfsmc)?item.skfsmc:" ") + "</p>" : "";
					 kcxszcspan = (xszd.kcxszc) ? $.i18n.get("kcxszc2") + ($.defined(item.kcxszc)?item.kcxszc:" ")  : "";
					 kcxszctbspan = (xszd.kcxszc) ? "<p>"+  ($.defined(item.kcxszc)?item.kcxszc:" ")  + "</p>" : "";
					 zhxsspan = (xszd.zhxs) ? $.i18n.get("zhouxs2") + ($.defined(item.zhxs)?item.zhxs:" ") : "";
					 zhxstbspan = (xszd.zhxs) ? "<p>" + ($.defined(item.zhxs)?item.zhxs:" ") + "</p>" : "";
					 zxsspan = (xszd.zxs) ?  $.i18n.get("zongxs2") + ($.defined(item.zxs)?item.zxs:" ") : "";
					 zxstbspan = (xszd.zxs) ? "<p>"+ ($.defined(item.zxs)?item.zxs:" ") + "</p>" : "";
					 xfspan = (xszd.xf) ?  $.i18n.get("xf2") + ($.defined(item.xf)?item.xf:" ") : "";
					 xftbspan = (xszd.xf) ? "<p>" +  ($.defined(item.xf)?item.xf:" ") + "</p>" : "";
					 kczxsspan = (xszd.kczxs) ?  $.i18n.get("kczxs2") + ($.defined(item.kczxs)?item.kczxs:" ") : "";
					 kczxstbspan = (xszd.kczxs) ? "<p>" + ($.defined(item.kczxs)?item.kczxs:" ") + "</p>" : "";

					 kchtbspan = (xszd.kch) ? "<p>"+ item.kch + "</p>" : "";
					 if($("#kbylsfxsdj").val() == '2' && $("#xxdm").val()=='10564'){
						if(item.jcma == item.jcmb){
							sjtbspan = (xszd.sj) ? "<p>"+ item.zcd + "</p>" : "";
						}else{
							sjtbspan = (xszd.sj) ? "<p>"+ "(" + item.jc + ")" + item.zcd + "</p>" : "";
						}
					 }else{
						 sjtbspan = (xszd.sj) ? "<p>"+ "(" + item.jc + ")" + item.zcd + "</p>" : "";
					 }
					 
					 cdtbspan = (xszd.cd) ? "<p>" + ((xszd.xq) ? item.xqmc : "") + ($("#xxdm").val()=='10564'?(" "+$.i18n.get("skdd2")):" ")+ item.cdmc + "</p>" : "";
					 jstbspan = (xszd.js) ? "<p>" + ($.defined(item.xm)?item.xm:"") + ((xszd.jszc && $.defined(item.zcmc)) ? "(" + item.zcmc + ")" : "") + ((xszd.zfj && $.defined(item.zfjmc)) ? "(" + item.zfjmc + ")" : "") + "</p>" : "";
					 zzmmtbspan = (xszd.zzmm) ? "<p>" + ($.defined(item.zzmm)?item.zzmm:" ") + "</p>": "";
					 jxbtbspan = (xszd.jxb) ? "<p>"+ item.jxbmc + "</p>" : "";
					 jxbzctbspan = (xszd.jxbzc) ? "<p>"+ item.jxbzc + "</p>" : "";
					 zzrlbspan = (xszd.zzrl) ? "<p>" + item.zzrl + "</p>" : "";
					 zyfxtbspan = (xszd.zyfx && item.zyfx_id != 'wfx') ? "<p>" + item.zyfxmc + "</p>" : "";
					 zyhxkcbjtbspan = (xszd.zyhxkcbj) ? "<p>"+ item.zyhxkcbj + "</p>" : "";		
					 cdlbmcspan = (xszd.cdlbmc) ? $.i18n.get("cdlbmc") + "：" + ($.defined(item.cdlbmc)?item.cdlbmc:""):"";
					 cdlbmctbspan = (xszd.cdlbmc) ? "<p>"+ ($.defined(item.cdlbmc)?item.cdlbmc:" ") + "</p>" : "";
					 ktmcspan = (xszd.ktmc) ? $.i18n.get("ktmc") + "：" + ($.defined(item.ktmc)?item.ktmc:""):"";
					 ktmctbspan = (xszd.ktmc) ? "<p>"+ ($.defined(item.ktmc)?item.ktmc:" ") + "</p>" : "";
					 kclbmcspan = (xszd.kclbmc) ? $.i18n.get("kclbmc") + "：" + ($.defined(item.kclbmc)?item.kclbmc:""):"";
					 kclbmctbspan = (xszd.kclbmc) ? "<p>"+ ($.defined(item.kclbmc)?item.kclbmc:" ") + "</p>" : "";
				}
				
				/*构造表格模式结构*/
				if($("#table1").find("#kbgrid_table_"+xqdm).size()==0){ //表格模式按校区分, 因校区的日时段结构可能不同
					var kbrsd; //获取日时段信息,得到节次结构,及上下午列是否显示
					$.ajax({
						type: 'POST', 
						url: _path+'/kbdy/bjkbdy_cxRsd.html',
						async: false,
						data: {"xnm":map.xnm, "xqm":map.xqm, "xqh_id": xqh_id},
						datatype: 'json',
						success: function(data){
							kbrsd = data; //获得数据
						}
					});
					if(($("#kbylsfxsdj").val()=='1'||$("#kbylsfxsdj").val()=='2') && $('button[data-toggle="tab"].btn-primary').attr('data-type')=='table'){
						$.ajax({
							type: 'POST', 
							url: _path+'/kbdy/jskbdy_cxXsdjList2.html',
							async: false,
							data: {"xnm":map.xnm, "xqm":map.xqm, "xqh_id": xqh_id},
							datatype: 'json',
							success: function(data){
								djList = data; //获得数据
							}
						});
						
						$.ajax({
							type: 'POST', 
							url: _path+'/kbdy/jskbdy_cxTimeModelList.html',
							async: false,
							data: {"xnm":map.xnm, "xqm":map.xqm, "xqh_id": xqh_id},
							datatype: 'json',
							success: function(data){
								timeList = data; //获得数据
							}
						});
						$.ajax({
							type: 'POST', 
							url: _path+'/kbdy/jskbdy_cxTimeModelListXj.html',
							async: false,
							data: {"xnm":map.xnm, "xqm":map.xqm, "xqh_id": xqh_id},
							datatype: 'json',
							success: function(data){
								timeListXj = data; //获得数据
							}
						});
					}
					var kbrjc; //获取日节次的起始时间和结束时间
					$.ajax({
						type: 'POST', 
						url: _path+'/kbdy/bjkbdy_cxRjc.html',
						async: false,
						data: {"xnm":map.xnm, "xqm":map.xqm, "xqh_id": xqh_id},
						datatype: 'json',
						success: function(data){
							kbrjc = data;
						}
					});
					if(kbrsd.length==0){
						$("#table1").empty().append("<h3 class='align-center'>"+$.i18n.get("alert7")+"</h3>");
						$("#table2").append("<h3 class='align-center'>"+$.i18n.get("alert7")+"</h3>");
						return false;
					}
					
                    var xqWidth = Math.floor(875/kblx)/10;
                    if (kbrsd[0].kbsfxs == '0') {
                    	xqWidth = Math.floor(950/kblx)/10;
                    }
					var tableHtml = "";
					tableHtml += "<table id='kbgrid_table_"+xqdm+"' class='table table-hover table-bordered text-center timetable1'>";
					requestMap["modelList[0].jsxm"]= map.jsxm;
					requestMap["modelList[0].lxdh"]=map.lxdh;
					var fdyxx = "";
					
					if($("#xxdm").val()=="12393"){
						if(map.jsxm!=undefined){
							fdyxx=fdyxx+"  辅导员:"+map.jsxm;
							// fdyxx=fdyxx+"  "+$.i18n.get("fdy2")+map.jsxm;
						}
						if(map.jsxm!=undefined){
							fdyxx=fdyxx+"  电话:"+map.lxdh;
							// fdyxx=fdyxx+"  "+$.i18n.get("alert13")+map.lxdh;
						}							
					}
					if($("#kbylsfxsdj").val()=='1' && $('button[data-toggle="tab"].btn-primary').attr('data-type')=='table'){
						if(kbrsd[0].kbsfxs=='1'){
							tableHtml += "<tr><td colspan='"+(2+kblx)+"'><div class='timetable_title'>"
							  +"<h6 class='pull-left'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+fdyxx+"</h6>"+tjkbmc
							tableHtml +="<h6 class='pull-right'>"+lxfsHtml1+"</h6>"
							tableHtml+= "</div><div class='pull-left'>"+xsbjHtml+"</div></td></tr>"
							  +"<tr><td width='7.5%'><span class='time'>"+$.i18n.get("sjd")+"</span></td><td width='5%'><span class='time'>"+$.i18n.get("jc")+"</span></td>";
							if(kblx==7 && qsxqj==7){
								tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[7]+"</span></td>";
								for(var j=1; j<=kblx-1; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}else{
								for(var j=1; j<=kblx; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}
							
							tableHtml+="</tr>";
							var m = 0;
							for(var j =0; j<timeList.length;j++){
								tableHtml+="<tr>";
								tableHtml+="<td rowspan='"+timeList[j].djgs+"'><span class='time'>"+timeList[j].sdmc+"</span></td>";
								for(var b = 0;b<timeList[j].djgs;b++){
									for(var k=0;k<djdzList.length;k++){
										if(djList[b+m].xsdj == djdzList[k].DJ){
											tableHtml+="<td><span class='time'>"+djdzList[k].DJMS+"</span></td>";
											if(kblx==7 && qsxqj==7){
												tableHtml+="<td rowspan=1 id='"+"7"+"-"+djList[b].xsdj+"' class='td_wrap'></td>";
												for(var k1 = 1; k1 <= kblx-1; k1++){
													tableHtml+="<td rowspan=1 id='"+k1+"-"+djList[b].xsdj+"' class='td_wrap'></td>";
												}
											}else{
												for(var k1 = 1; k1 <= kblx; k1++){
													tableHtml+="<td rowspan=1 id='"+k1+"-"+djList[b].xsdj+"' class='td_wrap'></td>";
												}
											}
											
											tableHtml+="</tr>";
											continue;
										}	
									}
								}
								m += timeList[j].djgs;
							}
						}else{
							tableHtml += "<tr><td colspan='"+(1+kblx)+"'><div class='timetable_title'>"
							  +"<h6 class='pull-left'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+fdyxx+"</h6>"+tjkbmc
							  
							tableHtml+="</div><div class='pull-left'>"+xsbjHtml+"</div></td></tr>"
							  +"<tr><td width='5%'><span class='time'>"+$.i18n.get("jc")+"</span></td>";
							if(kblx==7 && qsxqj==7){
								tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[7]+"</span></td>";
								for(var j=1; j<=kblx-1; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}else{
								for(var j=1; j<=kblx; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}
							tableHtml+="</tr>";
							var m = 0;
							for(var j =0; j<timeList.length;j++){
							//	tableHtml+="<tr>";
							//	tableHtml+="<td rowspan='"+timeList[j].djgs+"'><span class='time'>"+timeList[j].sdmc+"</span></td>";
								for(var b = 0;b<timeList[j].djgs;b++){
									for(var k=0;k<djdzList.length;k++){
										if(djList[b+m].xsdj == djdzList[k].DJ){
											tableHtml+="<tr><td><span class='time'>"+djdzList[k].DJMS+"</span></td>";
											if(kblx==7 && qsxqj==7){
												tableHtml+="<td rowspan=1 id='"+"7"+"-"+djList[b].xsdj+"' class='td_wrap'></td>";
												for(var k1 = 1; k1 <= kblx-1; k1++){
													tableHtml+="<td rowspan=1 id='"+k1+"-"+djList[b].xsdj+"' class='td_wrap'></td>";
												}
											}else{
												for(var k1 = 1; k1 <= kblx; k1++){
													tableHtml+="<td rowspan=1 id='"+k1+"-"+djList[b].xsdj+"' class='td_wrap'></td>";
												}
											}
											tableHtml+="</tr>";
											continue;
										}	
									}
								}
								m += timeList[j].djgs;
							}
						}
						
					}else if($("#kbylsfxsdj").val()=='2' && $('button[data-toggle="tab"].btn-primary').attr('data-type')=='table'){debugger
						if(kbrsd[0].kbsfxs=='1'){
							if ($("#xxdm").val() == "12841") {
								tableHtml += "<tr><td colspan='"+(2+kblx)+"' style='padding-bottom: 0'><div class='timetable_title' style='position: relative;padding-bottom: 20px;'>"
									+"<img src='"+_systemPath+"/images/12841_xiaohui.jpg' style='margin-right: 76px;position: absolute;left: 0;'>"
									+"<img src='"+_systemPath+"/images/12841_xiaoxun.jpg'>"
									+"<h6 class='pull-left' style='position: absolute;bottom: 0;font-weight: 800;font-size: 16px;color: #000;'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+fdyxx+"</h6>"
									+"<div style='position: absolute;bottom: 0;font-weight: 800;font-size: 16px;color: #000;right: 0;'><div style='text-align: right;'>" + tjkbmc + "</div><div style='text-align: right;'>专业：" + map.zymc + "</div></div>"
									+"</div>"
									+"<div class='pull-left' style='display: none;'>"+xsbjHtml+"</div></td></tr>"
									+"<tr><td width='7.5%'><span class='time'>"+$.i18n.get("sjd")+"</span></td><td width='5%'><span class='time'>"+$.i18n.get("jc")+"</span></td>";
							} else {
								tableHtml += "<tr><td colspan='"+(2+kblx)+"'><div class='timetable_title'>"
									+"<h6 class='pull-left'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+fdyxx+"</h6>"+tjkbmc
								tableHtml +="<h6 class='pull-right'>"+lxfsHtml1+"</h6>"	
								tableHtml+="</div><div class='pull-left'>"+xsbjHtml+"</div></td></tr>"
									+"<tr><td width='7.5%'><span class='time'>"+$.i18n.get("sjd")+"</span></td><td width='5%'><span class='time'>"+$.i18n.get("jc")+"</span></td>";
							}
							if(kblx==7 && qsxqj==7){
								tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[7]+"</span></td>";
								for(var j=1; j<=kblx-1; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}else{
								for(var j=1; j<=kblx; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}
							tableHtml+="</tr>";
							var m = 0;
							var n = 0;
							var xjCount = 0;
							for(var j =0; j<timeList.length;j++){
								tableHtml+="<tr>";
								tableHtml+="<td rowspan='"+timeList[j].djgs+"'><span class='time'>"+timeList[j].sdmc+"</span></td>";
								for(var b = 0;b<timeList[j].djgs;b++){
									if("12849"==$("#xxdm").val()){
										var xjms = djList[b+m].jcmc.replace(',','-');
										tableHtml+="<td><span class='time'>"+$.i18n.get("di")+xjms+$.i18n.get("jie")+"</span></td>";
									}else if($("#kbdjmsxsfs").val()=='1'){
								    	tableHtml+="<td><span class='time'>"+convertToChinese(djList[b+m].xsdj)+"</span></td>";
								    }else{
								    	var xjms = '';
                                    	var xjgs = djList[b+m].xjgs;
                                    	for (var bb = xjCount+1;bb<=xjCount+xjgs;bb++){
                                    		xjms+=convertToChinese(bb);
                                    	}
                                    	if($("#xxdm").val()=='10564'){
                                    		xjms = xjCount+1 + "-" + (xjCount+xjgs);
                                    	}
                                    	debugger
								    	tableHtml+="<td><span class='time'>"+$.i18n.get("di")+xjms+$.i18n.get("jie")+"</span></td>";
								    	//tableHtml+="<td><span class='time'>"+'第'+convertToChinese(djList[b+m])+'节'+"</span></td>";
								    }
								    if(kblx==7 && qsxqj==7){
										tableHtml+="<td rowspan=1 id='"+"7"+"-"+djList[b+m].xsdj+"' class='td_wrap'></td>";
										for(var k1 = 1; k1 <= kblx-1; k1++){
											tableHtml+="<td rowspan=1 id='"+k1+"-"+djList[b+m].xsdj+"' class='td_wrap'></td>";
										}
									}else{
										for(var k1 = 1; k1 <= kblx; k1++){
											tableHtml+="<td rowspan=1 id='"+k1+"-"+djList[b+m].xsdj+"' class='td_wrap'></td>";
										}
									}
									tableHtml+="</tr>";
									n+=1;
									xjCount +=djList[b+m].xjgs;
								}
								
								m += timeList[j].djgs;
							}
						}else{
							if ($("#xxdm").val() == "12841") {
								tableHtml += "<tr><td colspan='"+(2+kblx)+"' style='padding-bottom: 0'><div class='timetable_title' style='position: relative;padding-bottom: 20px;'>"
									+"<img src='"+_systemPath+"/images/12841_xiaohui.jpg' style='margin-right: 76px;position: absolute;left: 0;'>"
									+"<img src='"+_systemPath+"/images/12841_xiaoxun.jpg'>"
									+"<h6 class='pull-left' style='position: absolute;bottom: 0;font-weight: 800;font-size: 16px;color: #000;'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+fdyxx+"</h6>"
									+"<div style='position: absolute;bottom: 0;font-weight: 800;font-size: 16px;color: #000;right: 0;'><div style='text-align: right;'>" + tjkbmc + "</div><div style='text-align: right;'>专业：" + map.zymc + "</div></div>"
									+"</div>"
									+"<div class='pull-left' style='display: none;'>"+xsbjHtml+"</div></td></tr>"
									+"<tr><td width='5%'><span class='time'>"+$.i18n.get("jc")+"</span></td>";
							} else {
								tableHtml += "<tr><td colspan='"+(1+kblx)+"'><div class='timetable_title'>"
									+"<h6 class='pull-left'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+fdyxx+"</h6>"+tjkbmc
								tableHtml +="<h6 class='pull-right'>"+lxfsHtml1+"</h6>"
								tableHtml+="</div><div class='pull-left'>"+xsbjHtml+"</div></td></tr>"
									+"<tr><td width='5%'><span class='time'>"+$.i18n.get("jc")+"</span></td>";
							}
							if(kblx==7 && qsxqj==7){
								tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[7]+"</span></td>";
								for(var j=1; j<=kblx-1; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}else{
								for(var j=1; j<=kblx; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}
							tableHtml+="</tr>";
							var m = 0;
							var xjCount = 0;
							for(var j =0; j<timeList.length;j++){
								for(var b = 0;b<timeList[j].djgs;b++){
									if("12849"==$("#xxdm").val()){
										var xjms = djList[b+m].jcmc.replace(',','-');
										tableHtml+="<td><span class='time'>"+$.i18n.get("di")+xjms+$.i18n.get("jie")+"</span></td>";
									}else if($("#kbdjmsxsfs").val()=='1'){
								    	tableHtml+="<td><span class='time'>"+convertToChinese(djList[b+m].xsdj)+"</span></td>";
								    }else{
								    	var xjms = '';
                                    	var xjgs = djList[b+m].xjgs;
                                    	for (var bb = xjCount+1;bb<=xjCount+xjgs;bb++){
                                    		xjms+=convertToChinese(bb);
                                    	}
                                    	if($("#xxdm").val()=='10564'){
                                    		xjms = xjCount+1 + "-" + (xjCount+xjgs);
                                    	}
								    	tableHtml+="<td><span class='time'>"+$.i18n.get("di")+xjms+$.i18n.get("jie")+"</span></td>";
								    }
								    if(kblx==7 && qsxqj==7){
										tableHtml+="<td rowspan=1 id='"+"7"+"-"+djList[b+m].xsdj+"' class='td_wrap'></td>";
										for(var k1 = 1; k1 <= kblx-1; k1++){
											tableHtml+="<td rowspan=1 id='"+k1+"-"+djList[b+m].xsdj+"' class='td_wrap'></td>";
										}
									}else{
										for(var k1 = 1; k1 <= kblx; k1++){
											tableHtml+="<td rowspan=1 id='"+k1+"-"+djList[b+m].xsdj+"' class='td_wrap'></td>";
										}
									}
									tableHtml+="</tr>";
									xjCount +=djList[b+m].xjgs;
								}
								m += timeList[j].djgs;
							}
						}
					}else{
						if(kbrsd[0].kbsfxs=='0'){ //如果时间段不显示
							tableHtml += "<tr><td colspan='"+(1+kblx)+"'><div class='timetable_title'>"
							  +"<h6 class='pull-left'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+fdyxx+"</h6>"+tjkbmc
							tableHtml +="<h6 class='pull-right'>"+lxfsHtml1+"</h6>"
							tableHtml+="</div><div class='pull-left'>"+xsbjHtml+"</div></td></tr>"
							  +"<tr><td width='5%'><span class='time'>"+$.i18n.get("jc")+"</span></td>";
							if(kblx==7 && qsxqj==7){
								tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[7]+"</span></td>";
								for(var j=1; j<=kblx-1; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}else{
								for(var j=1; j<=kblx; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}
							tableHtml+="</tr>";
							var qsjc = 0; //节次下标  起始为0
							$.each(kbrsd, function(index, item){
								var rsdzjs = item.rsdzjs;
								for(var k = 1; k <= rsdzjs; k++){
									//var jcmcStr = $.founded(kbrjc[qsjc].rsdjcmc)?kbrjc[qsjc].rsdjcmc:kbrjc[qsjc].jcmc;
									var jcmcStr = $.founded(kbrjc[qsjc].rsdjcmc)?kbrjc[qsjc].rsdjcmc+(rsdzjs>1?k:''):kbrjc[qsjc].jcmc;
									if(kbjcxfsfxsqsjssj == 1){
										if($("#xxdm").val()=='10828'){
											tableHtml+="<tr><td><span class='festival'>"+jcmcStr+"</span><span>起:"+kbrjc[qsjc].qssj+"</span><br><span>止:"+kbrjc[qsjc].jssj+"</span></td>";	
										}else{
											tableHtml+="<tr><td><span class='festival'>"+jcmcStr+"</span><span>"+kbrjc[qsjc].qssj+"</span><br><span>"+kbrjc[qsjc].jssj+"</span></td>";
										}
									}else{
										tableHtml+="<tr><td><span class='festival'>"+jcmcStr+"</span></td>";
									}
									if(kblx==7 && qsxqj==7){
										tableHtml+="<td rowspan=1 id='"+"7"+"-"+kbrjc[qsjc].jcmc+"' class='td_wrap'></td>";
										for(var k1 = 1; k1 <= kblx-1; k1++){
											tableHtml+="<td rowspan=1 id='"+k1+"-"+kbrjc[qsjc].jcmc+"' class='td_wrap'></td>";
										}
									}else{
										for(var k1 = 1; k1 <= kblx; k1++){
											tableHtml+="<td rowspan=1 id='"+k1+"-"+kbrjc[qsjc].jcmc+"' class='td_wrap'></td>";
										}
									}
									qsjc += 1;
									tableHtml+="</tr>";
								}
							});
						}else{ //时间段显示
							tableHtml += "<tr><td colspan='"+(2+kblx)+"'><div class='timetable_title'>"
							  +"<h6 class='pull-left'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+fdyxx+"</h6>"+tjkbmc
							tableHtml +="<h6 class='pull-right'>"+lxfsHtml1+"</h6>"
							tableHtml+="</div><div class='pull-left'>"+xsbjHtml+"</div></td></tr>"
							  +"<tr><td width='7.5%'><span class='time'>"+$.i18n.get("sjd")+"</span></td><td width='5%'><span class='time'>"+$.i18n.get("jc")+"</span></td>";
							if(kblx==7 && qsxqj==7){
								tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[7]+"</span></td>";
								for(var j=1; j<=kblx-1; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}else{
								for(var j=1; j<=kblx; j++){
									tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[j]+"</span></td>";
								}
							}
							tableHtml+="</tr>";
							var qsjc = 0; //节次下标  起始为0
							$.each(kbrsd, function(index, item){
								var rsdzjs = item.rsdzjs;
								var rsdmc = item.rsdmc;
								var rsdywmc = item.rsdywmc;
								for(var k = 1; k <= rsdzjs; k++){
									tableHtml+="<tr>";
									if(k==1){
										//tableHtml+="<td rowspan='"+rsdzjs+"'><span class='time'>"+rsdmc+"</span></td>";
										tableHtml+="<td rowspan='"+rsdzjs+"'><span class='time'>"+(localeKey=='en_US'?rsdywmc:rsdmc)+"</span></td>";
									}
									//var jcmcStr = $.founded(kbrjc[qsjc].rsdjcmc)?kbrjc[qsjc].rsdjcmc:kbrjc[qsjc].jcmc;
									var jcmcStr = $.founded(kbrjc[qsjc].rsdjcmc)?kbrjc[qsjc].rsdjcmc+(rsdzjs>1?k:''):kbrjc[qsjc].jcmc;
									if(kbjcxfsfxsqsjssj == 1){
										if($("#xxdm").val()=='10828'){
											tableHtml+="<td><span class='festival'>"+jcmcStr+"</span><span>起:"+kbrjc[qsjc].qssj+"</span><br><span>止:"+kbrjc[qsjc].jssj+"</span></td>";
										}else{
											tableHtml+="<td><span class='festival'>"+jcmcStr+"</span><span>"+kbrjc[qsjc].qssj+"</span><br><span>"+kbrjc[qsjc].jssj+"</span></td>";
										}
									}else{
										tableHtml+="<td><span class='festival'>"+jcmcStr+"</span></td>";
									}
									if(kblx==7 && qsxqj==7){
										tableHtml+="<td rowspan=1 id='"+"7"+"-"+kbrjc[qsjc].jcmc+"' class='td_wrap'></td>";
										for(var k1 = 1; k1 <= kblx-1; k1++){
											tableHtml+="<td rowspan=1 id='"+k1+"-"+kbrjc[qsjc].jcmc+"' class='td_wrap'></td>";
										}
									}else{
										for(var k1 = 1; k1 <= kblx; k1++){
											tableHtml+="<td rowspan=1 id='"+k1+"-"+kbrjc[qsjc].jcmc+"' class='td_wrap'></td>";
										}
									}
									qsjc += 1;
									tableHtml+="</tr>";
								}
							});
						}
					}
					

						if(sjkcHtml.length>0|| qtkcHtml.length>0|| jxhjkcHtml.length>0|| lxfsHtml.length>0){
							if($("#xxdm").val()=='10564'){
								tableHtml += "<tr id='sjkDiv' style='position: sticky; bottom: -15px;background-color:#f5f5f5'><td colspan='"+(3+kblx)+"' style='text-align:left;'><div class='timetable_title'>";
							}else{
								tableHtml += "<tr><td colspan='"+(3+kblx)+"' style='text-align:left;'><div class='timetable_title'>";
							}
							if($("#kbylsfxstb").val()=='1'){
								if(sjkcHtml.length>0){
							    	//tableHtml += "<span class='time red'>实践课程：</span>"+sjkcHtml+"<br>";
							    	tableHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sjkc2")+"</span><span style='font:  18px/25px SimSun;'>"+sjkcHtml+"</span><br>";
							    }
							    if(qtkcHtml.length>0){
							    	//tableHtml += "<span class='time red'>其他课程：</span>"+qtkcHtml+"<br>";
									if($("#xxdm").val()=='12308'){
										tableHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sxz2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
									}else{
										tableHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("qtkc2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
									}
							    }
							    if(jxhjkcHtml.length>0){
							    	tableHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("jxhj2")+"</span><span style='font:  18px/25px SimSun;'>"+jxhjkcHtml+"</span>";
							    }
							}else{
								if(sjkcHtml.length>0){
							    	//tableHtml += "<span class='time red'>实践课程：</span>"+sjkcHtml+"<br>";
							    	tableHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sjkc2")+"</span><span style='font:  18px/25px SimSun;'>"+sjkcHtml+"</span><br>";
							    }
							    if(qtkcHtml.length>0){
							    	//tableHtml += "<span class='time red'>其他课程：</span>"+qtkcHtml+"<br>";
									if($("#xxdm").val()=='12308'){
										tableHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sxz2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
									}else{
										tableHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("qtkc2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
									}
							    }
							    if(jxhjkcHtml.length>0){
							    	tableHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("jxhj2")+"</span><span style='font:  18px/25px SimSun;'>"+jxhjkcHtml+"</span>";
							    }
							}
							
						    if(lxfsHtml.length>0){
						    	tableHtml += "<span style='font:  18px/25px SimSun;'>"+lxfsHtml+"</span>";
						    }
						    tableHtml += "</div></td></tr>";
						}
					tableHtml += "</table>";
					$("#table1").append(tableHtml);
				}
				/*构造表格模式结构结束*/
				
				/*构造列表模式结构*/
				if(i==0){
					var listHtml = "";
					listHtml += "<table id='kblist_table' class='table table-hover table-bordered text-center timetable'>"
							+"<tbody><tr><td colspan='4'><div class='timetable_title'>"
							+"<h6 class='pull-left'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+"</h6>"+tjkbmc
					listHtml +="<h6 class='pull-right'>"+lxfsHtml1+"</h6>"		
					listHtml+="</div><div class='pull-left'>"+xsbjHtml+"</div></td></tr>"
							+"<tr class='tbody_head'><td>"+$.i18n.get("xqj")+"</td><td>"+$.i18n.get("jc")+"</td><td>"+$.i18n.get("kbxx")+"</td></tr></tbody>";
					
					if(kblx==7 && qsxqj==7){
						//tableHtml+="<td width='"+xqWidth+"%'><span class='time'>"+data.xqjmcMap[7]+"</span></td>";
						listHtml += "<tbody id='xq_"+"7"+"' style='display: none'><tr><td id='xq_rowspan_"+"7"+"' rowspan='0' width='50'><span class='week'>"+data.xqjmcMap[7]+"</span></td></tr></tbody>";
						for(var j=1; j<=kblx-1; j++){
							listHtml += "<tbody id='xq_"+j+"' style='display: none'><tr><td id='xq_rowspan_"+j+"' rowspan='0' width='50'><span class='week'>"+data.xqjmcMap[j]+"</span></td></tr></tbody>";
						}
					}else{
						for(var j=1; j<=kblx; j++){
							listHtml += "<tbody id='xq_"+j+"' style='display: none'><tr><td id='xq_rowspan_"+j+"' rowspan='0' width='50'><span class='week'>"+data.xqjmcMap[j]+"</span></td></tr></tbody>";
						}
					}
					if(sjkcHtml.length>0 || qtkcHtml.length>0 || jxhjkcHtml.length>0|| lxfsHtml.length>0){
						listHtml += "<tbody><tr><td colspan='4' style='text-align:left;'><div class='timetable_title' >";
						if($("#kbylsfxstb").val()=='1'){
							if(sjkcHtml.length>0){
						    	listHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sjkc2")+"</span><span style='font:  18px/25px SimSun;'>"+sjkcHtml+"</span><br>";
						    }
						    if(qtkcHtml.length>0){
								if($("#xxdm").val()=='12308'){
									listHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sxz2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
								}else{
									listHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("qtkc2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
								}
						    }
						    if(jxhjkcHtml.length>0){
						    	listHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("jxhj2")+"</span><span style='font:  18px/25px SimSun;'>"+jxhjkcHtml+"</span>";
						    }
						}else{
							if(sjkcHtml.length>0){
						    	listHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sjkc2")+"</span><span style='font:  18px/25px SimSun;'>"+sjkcHtml+"</span><br>";
						    }
						    if(qtkcHtml.length>0){
								if($("#xxdm").val()=='12308'){
									listHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sxz2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
								}else{
									listHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("qtkc2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
								}
						    }
						    if(jxhjkcHtml.length>0){
						    	listHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("jxhj2")+"</span><span style='font:  18px/25px SimSun;'>"+jxhjkcHtml+"</span>";
						    }
						}
					    if(lxfsHtml.length>0){
					    	listHtml += "<span style='font:  18px/25px SimSun;'>"+lxfsHtml+"</span>";
					    }
					    listHtml += "</div></td></tr></tbody>";
					}					
					listHtml += "</table>";
					$("#table2").append(listHtml);
				}
				/*构造列表模式结构结束*/	
				
				/*填入表格课表信息*/
				var jcor = "";
				var jcValueArr = "";
				var jcValue1="";
				var jcValue2="";
				var tdEle = "";
				var tdHtml = "";
				var xsdj = "";
				if(($("#kbylsfxsdj").val()=='1'||$("#kbylsfxsdj").val()=='2') && $('button[data-toggle="tab"].btn-primary').attr('data-type')=='table'){
					xsdj = item.xsdj;
					tdEle = $("#kbgrid_table_"+xqdm).find("#"+xqj+"-"+item.xsdj);
					var kcmc = item.kcmc+(item.xslxbj||'');
					if("1" == xtkchb){
						if(preKcmc != kcmc || preJc != xsdj || preXqj != xqj){
							tdHtml = "<div class='timetable_con text-left'><p class='title'>"+item.kcmc+(item.xslxbj||'')+"</p>"+kchtbspan+sjtbspan+cdtbspan+jstbspan+zzmmtbspan+jxbtbspan+ktmctbspan+jxbzctbspan+zzrlbspan+zyfxspan+kcxzjctbspan+jxbrstbspan+xkrstbspan+khfstbspan+ksfstbspan+xkbztbspan+zxxxtbspan
							+skfstbspan+kcxszctbspan+zhxstbspan+zxstbspan+xftbspan+kczxstbspan+zyhxkcbjtbspan+cdlbmctbspan+skpthyhtbspan+jtskwztbspan+qqqhtbspan+kclbmctbspan+"</div>";
						}else{
							tdHtml = "<div class='timetable_con text-left'>"+kchtbspan+sjtbspan+cdtbspan+jstbspan+zzmmtbspan+jxbtbspan+ktmctbspan+jxbzctbspan+zzrlbspan+zyfxspan+kcxzjctbspan+jxbrstbspan+xkrstbspan+khfstbspan+ksfstbspan+xkbztbspan+zxxxtbspan
							+skfstbspan+kcxszctbspan+zhxstbspan+zxstbspan+xftbspan+kczxstbspan+zyhxkcbjtbspan+cdlbmctbspan+skpthyhtbspan+jtskwztbspan+qqqhtbspan+kclbmctbspan+"</div>";
						}
					}else{
						tdHtml = "<div class='timetable_con text-left'><p class='title'>"+item.kcmc+(item.xslxbj||'')+"</p>"+kchtbspan+sjtbspan+cdtbspan+jstbspan+zzmmtbspan+jxbtbspan+ktmctbspan+jxbzctbspan+zzrlbspan+zyfxspan+kcxzjctbspan+jxbrstbspan+xkrstbspan+khfstbspan+ksfstbspan+xkbztbspan+zxxxtbspan
							+skfstbspan+kcxszctbspan+zhxstbspan+zxstbspan+xftbspan+kczxstbspan+zyhxkcbjtbspan+cdlbmctbspan+skpthyhtbspan+jtskwztbspan+qqqhtbspan+kclbmctbspan+"</div>";
					}
					tdEle.append(tdHtml).attr("rowspan", 1);
					// 赋值
					preKcmc = kcmc;
					preJc = xsdj;
					preXqj = xqj
				}else{
					jcor = item.jcor;
					jcValueArr = jcor.split("-");
					jcValue1 = jcValueArr[0];
					jcValue2 = jcValueArr[1];
					tdEle = $("#kbgrid_table_"+xqdm).find("#"+xqj+"-"+jcValue1);
					if(pkbj == -1){
						if($("#tjkbbkksfxszc").val()=='1'){
							//var tdHtml = "<div class='timetable_con text-left'><p class='title'>"+item.kcmc+"("+item.zcd+")"+"</p></div>";
							var tdHtml = "<div class='timetable_con text-left'><p class='title'>"+item.kcmc+"</p>"+sjtbspan+"</div>";
						}else{
							 var tdHtml = "<div class='timetable_con text-left'><p class='title'>"+item.kcmc+"</p></div>";
						}								  
					}else{
						var kcmc = item.kcmc+(item.xslxbj||'');
						if("1" == xtkchb){
							if(preKcmc != kcmc || preJc != jcor || preXqj != xqj){
								var tdHtml = "<div class='timetable_con text-left'><p class='title'>"+item.kcmc+(item.xslxbj||'')+"</p>"+kchtbspan+sjtbspan+cdtbspan+jstbspan+zzmmtbspan+jxbtbspan+ktmctbspan+jxbzctbspan+zzrlbspan+zyfxspan+kcxzjctbspan+jxbrstbspan+xkrstbspan+khfstbspan+ksfstbspan+xkbztbspan+zxxxtbspan
								+skfstbspan+kcxszctbspan+zhxstbspan+zxstbspan+xftbspan+kczxstbspan+zyhxkcbjtbspan+cdlbmctbspan+skpthyhtbspan+jtskwztbspan+qqqhtbspan+kclbmctbspan+"</div>";
							}else{
								var tdHtml = "<div class='timetable_con text-left'>"+kchtbspan+sjtbspan+cdtbspan+jstbspan+zzmmtbspan+jxbtbspan+ktmctbspan+jxbzctbspan+zzrlbspan+zyfxspan+kcxzjctbspan+jxbrstbspan+xkrstbspan+khfstbspan+ksfstbspan+xkbztbspan+zxxxtbspan
								+skfstbspan+kcxszctbspan+zhxstbspan+zxstbspan+xftbspan+kczxstbspan+zyhxkcbjtbspan+cdlbmctbspan+skpthyhtbspan+jtskwztbspan+qqqhtbspan+kclbmctbspan+"</div>";
							}
						}else{
							var tdHtml = "<div class='timetable_con text-left'><p class='title'>"+item.kcmc+(item.xslxbj||'')+"</p>"+kchtbspan+sjtbspan+cdtbspan+jstbspan+zzmmtbspan+jxbtbspan+ktmctbspan+jxbzctbspan+zzrlbspan+zyfxspan+kcxzjctbspan+jxbrstbspan+xkrstbspan+khfstbspan+ksfstbspan+xkbztbspan+zxxxtbspan
							+skfstbspan+kcxszctbspan+zhxstbspan+zxstbspan+xftbspan+kczxstbspan+zyhxkcbjtbspan+cdlbmctbspan+skpthyhtbspan+jtskwztbspan+qqqhtbspan+kclbmctbspan+"</div>";
						}
					}
					// 赋值
					preKcmc = kcmc;
					preJc = jcor;
					preXqj = xqj
					tdEle.append(tdHtml).attr("rowspan", jcValue2-jcValue1+1);
					for(var j = jcValue1*1+1; j <= jcValue2; j++){
						$("#kbgrid_table_"+xqdm).find("#"+xqj+"-"+j).remove();
					}
				}
				

				/*填入表格课表信息结束*/
				
				/*填入列表课表信息*/
				var tbodyEle = $("#xq_"+xqj);
				tbodyEle.show(); //显示
				var xqRowSpanEle = $("#xq_rowspan_"+xqj);
				var rsp = xqRowSpanEle.attr("rowspan")||0;
				xqRowSpanEle.attr("rowspan", rsp*1+1); //rowspan + 1
				//合并单行显示
				var listspan = "";
				if(pkbj == -1){
					if($("#tjkbbkksfxszc").val()=='1'){
						var listspan = "<td id='jc_"+xqj+"-"+jcs+"' rowspan=1><span class='festival'>"+($.founded(item.jc)?item.jc:jcs).replace("节","")+"</span></td>"
						+"<td><div class='timetable_con text-left'>"+kcmcspan+"<p>"+sjspan+"</p>"
						+"</div></td>";
					}else{
						var listspan = "<td id='jc_"+xqj+"-"+jcs+"' rowspan=1><span class='festival'>"+($.founded(item.jc)?item.jc:jcs).replace("节","")+"</span></td>"
						+"<td><div class='timetable_con text-left'>"+kcmcspan
						+"</div></td>";
					}			  
				}else{
					if($("#kbylsfxstb").val()=='1'){
						listspan= "<td id='jc_"+xqj+"-"+jcs+"' rowspan=1><span class='festival'>"+($.founded(item.jc)?item.jc:jcs).replace("节","")+"</span></td>"
						+"<td><div class='timetable_con text-left'>"+kcmcspan
						+"<p>"+kchspan+sjspan+cdspan+jsspan+zzmmspan+jxbspan+ktmcspan+jxbzcspan+zzrlspan+zyfxspan+kcxzjcspan+jxbrsspan+xkrsspan+khfsspan+ksfsspan+xkbzspan+zxxxspan+skfsspan+kcxszcspan+zhxsspan+zxsspan+xfspan+kczxsspan+zyhxkcbjspan+cdlbmcspan+skpthyhspan+jtskwzspan+qqqhspan+kclbmcspan+"</p></div></td>";
					}else{
						listspan= "<td id='jc_"+xqj+"-"+jcs+"' rowspan=1><span class='festival'>"+($.founded(item.jc)?item.jc:jcs).replace("节","")+"</span></td>"
						+"<td><div class='timetable_con text-left'>"+kcmcspan
						+"<p>"+kchspan+" "+sjspan+" "+cdspan+" "+jsspan+" "+zzmmspan+" "+jxbspan+" "+jxbzcspan+" "+ktmcspan+" "+zzrlspan+" "+zyfxspan+" "+kcxzjcspan+" "+jxbrsspan+" "+xkrsspan+" "+khfsspan+" "+ksfsspan+" "+xkbzspan+" "+zxxxspan+" "+skfsspan+" "+kcxszcspan+" "+zhxsspan+" "+zxsspan+" "+xfspan+" "+kczxsspan+" "+zyhxkcbjspan+" "+cdlbmcspan+" "+skpthyhspan+" "+jtskwzspan+" "+qqqhspan+" "+kclbmcspan+"</p></div></td>";
					}
				}
				
				if(tbodyEle.find("#jc_"+xqj+"-"+jcs).size()==0){ // 如果没插入过这个节次
					if(xqRowSpanEle.attr("rowspan")==1){ //如果这个星期tr没被插入过
						xqRowSpanEle.parent().append(listspan);
					}else{ //如果这个星期tr插入过其他节次
						tbodyEle.append("<tr>"+listspan+"</tr>");
					}
				}else{ //如果插入过这个节次,那么要合并
					$("#jc_"+xqj+"-"+jcs).attr("rowspan", function(index, attr){return attr*1+1;});
					if(pkbj == -1){
					 $("#jc_"+xqj+"-"+jcs).parent().parent().append("<tr>"
							+"<td><div class='timetable_con text-left'>"+kcmcspan
							+"</div></td></tr>");
					}else{
						if($("#kbylsfxstb").val()=='1'){
							$("#jc_"+xqj+"-"+jcs).parent().parent().append("<tr>"
									+"<td><div class='timetable_con text-left'>"+kcmcspan
									+"<p>"+kchspan+sjspan+cdspan+jsspan+zzmmspan+jxbspan+ktmcspan+jxbzcspan+zyfxspan+kcxzjcspan+jxbrsspan+xkrsspan+khfsspan+ksfsspan+xkbzspan+zxxxspan+skfsspan+kcxszcspan+zhxsspan+zxsspan+xfspan+kczxsspan+zyhxkcbjspan+cdlbmcspan+skpthyhspan+jtskwzspan+qqqhspan+kclbmcspan+"</p></div></td></tr>");
						}else{
							$("#jc_"+xqj+"-"+jcs).parent().parent().append("<tr>"
									+"<td><div class='timetable_con text-left'>"+kcmcspan
									+"<p>"+kchspan+" "+sjspan+" "+cdspan+" "+jsspan+" "+zzmmspan+" "+jxbspan+" "+jxbzcspan+" "+ktmcspan+" "+zzrlspan+" "+zyfxspan+" "+kcxzjcspan+" "+jxbrsspan+" "+xkrsspan+" "+khfsspan+" "+ksfsspan+" "+xkbzspan+" "+zxxxspan+" "+skfsspan+" "+kcxszcspan+" "+zhxsspan+" "+zxsspan+" "+xfspan+" "+kczxsspan+" "+zyhxkcbjspan+" "+cdlbmcspan+" "+skpthyhspan+" "+jtskwzspan+" "+qqqhspan+" "+kclbmcspan+"</p></div></td></tr>");
						}
					  
					}
					
				}
				/*填入列表课表信息结束*/
				
			}
			
			//课表List为空，实践课List或者教学环节课程List不为空
			if (kbList.length == 0 && (sjkList.length > 0||jxhjkcList.length >0)) {
				var tableHtml = "";
				tableHtml += "<table id='kbgrid_table_"+xqdm+"' class='table table-hover table-bordered text-center timetable1' style='width:98%;margin-left:10px'>";
				if($("#xxdm").val()=='10564'){
					tableHtml += "<tr id='sjkDiv' style='position: sticky; bottom: -15px;background-color:#f5f5f5'><td colspan='"+(1+kblx)+"'><div class='timetable_title'>"
					  +"<h6 class='pull-left'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+"</h6>"+tjkbmc+"</div><div class='pull-left'>"+xsbjHtml+"</div></td></tr>";
				}else{
					tableHtml += "<tr><td colspan='"+(1+kblx)+"'><div class='timetable_title'>"
					  +"<h6 class='pull-left'>"+map.xnmc+$.i18n.get("xn")+diHtml+map.xqmmc+$.i18n.get("xq")+"</h6>"+tjkbmc+"</div><div class='pull-left'>"+xsbjHtml+"</div></td></tr>";
				}
				if(sjkcHtml.length>0 || qtkcHtml.length>0 ||jxhjkcHtml.length>0|| lxfsHtml.length>0){
					tableHtml += "<tbody><tr><td colspan='"+(1+kblx)+"' style='text-align:left;'><div class='timetable_title' >";
					if($("#kbylsfxstb").val()=='1'){
						if(sjkcHtml.length>0){
					    	tableHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sjkc2")+"</span><span style='font:  18px/25px SimSun;'>"+sjkcHtml+"</span><br>";
					    }
					    if(qtkcHtml.length>0){
							if($("#xxdm").val()=='12308'){
								tableHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sxz2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
							}else{
								tableHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("qtkc2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
							}
					    }
					    if(jxhjkcHtml.length>0){
					    	tableHtml += "<span class='red' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("jxhj2")+"</span><span style='font:  18px/25px SimSun;'>"+jxhjkcHtml+"</span>";
					    }
					}else{
						if(sjkcHtml.length>0){
					    	tableHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sjkc2")+"</span><span style='font:  18px/25px SimSun;'>"+sjkcHtml+"</span><br>";
					    }
					    if(qtkcHtml.length>0){
							if($("#xxdm").val()=='12308'){
								tableHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("sxz2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
							}else{
								tableHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("qtkc2")+"</span><span style='font:  18px/25px SimSun;'>"+qtkcHtml+"</span><br>";
							}
					    }
					    if(jxhjkcHtml.length>0){
					    	tableHtml += "<span class='black' style='font: normal 18px/25px microsoft YaHei;'>"+$.i18n.get("jxhj2")+"</span><span style='font:  18px/25px SimSun;'>"+jxhjkcHtml+"</span>";
					    }
					}
				    if(lxfsHtml.length>0){
				    	tableHtml += "<span style='font:  18px/25px SimSun;'>"+lxfsHtml+"</span>";
				    }
				    tableHtml += "</div></td></tr></tbody>";
				}	
			    tableHtml += "</table>";
			    $("#table1").append(tableHtml);
			    $("#table2").append(tableHtml);
			    
			}
			if ($.founded(bzxxHtml) && $("#kbsfxsxqbzxx").val()=='1') {
            	if($("#kbylsfxstb").val()=='1'){
	                $("#table1").append("<font color = 'blue'>备注：" + bzxxHtml + "</font>");
	                $("#table2").append("<font color = 'blue'>备注：" + bzxxHtml + "</font>");
            	}else{
            		$("#table1").append("备注：" + bzxxHtml );
	                $("#table2").append("备注：" + bzxxHtml);
            	}
            }
			if(($("#xxdm").val()=='99999'||$("#xxdm").val()=='10110')&&$.founded(bzxxHtml)){
				if($("#kbylsfxstb").val()=='1'){
	                $("#table1").append("<font color = 'blue'>" + bzxxHtml + "</font>");
	                $("#table2").append("<font color = 'blue'>" + bzxxHtml + "</font>");
            	}else{
            		$("#table1").append( bzxxHtml );
	                $("#table2").append( bzxxHtml);
            	}
			}
		}
	});
}
//处理0~99数字转为中文
function convertToChinese(xsdj){
	var num= [$.i18n.get("zero"),$.i18n.get("one"),$.i18n.get("two"),$.i18n.get("three"),$.i18n.get("four"),$.i18n.get("five"),
		$.i18n.get("six"),$.i18n.get("seven"),$.i18n.get("eight"),$.i18n.get("nine"),$.i18n.get("ten")];
	var djms = [];
	var len = xsdj.toString().length;
	if(len == 1){
		djms.push(num[xsdj.toString().charAt(0)]);
	}else if(len ==2){
		if(xsdj == 10){
			djms.push(num[10]);
		}else if(xsdj > 10 || xsdj< 20){
			djms.push($.i18n.get("ten")+num[xsdj.toString().charAt(1)]);
		}else{
			djms.push(num[xsdj.toString().charAt(0)]+$.i18n.get("ten")+num[xsdj.toString().charAt(1)]);
		}
	}
	return djms.join().replaceAll(',','').replaceAll($.i18n.get("zero"),'');
	
}
jQuery(function($){
	$("#table1").backdrop("show");
	var map = window.api.data.map;
	var tjkbmc = window.api.data.tjkbmc;
	
	if(typeof(xszd)==="undefined"){
		var xszd={};
		//获取配置的显示字段信息
		$.ajaxSetup({async : false});		
		$.post(_path + '/kbdy/bjkbdy_cxKbzdxsxx.html', {"kbzl":"bj","doType":"query"} , function(rst){
			$.each(rst,function(index,item){
				xszd[item.ZDM] = item.SFXS == "1";
			});
		});
		$.ajaxSetup({async : true});
		cxKbContent(map, tjkbmc, xszd);
	}else{
		cxKbContent(map, tjkbmc, xszd);
	}	
	
	$("#table1").backdrop("hide");
	$('button[data-toggle="tab"]').click(function(e) {
		$('button[data-toggle="tab"]').removeClass("btn-primary");
		$(this).addClass("btn-primary");
		$('#shcPDF').attr('data-type', $(this).attr('data-type'));
	});

	$('#shcPDF').click(function(){
		var requestMap = {};
		requestMap["xszd.kch"]=xszd.kch;
		requestMap["xszd.sj"]=xszd.sj;
		requestMap["xszd.cd"]=xszd.cd;
	    requestMap["xszd.js"]=xszd.js;
		requestMap["xszd.jszc"]=xszd.jszc;
		requestMap["xszd.jxb"]=xszd.jxb;
		requestMap["xszd.jxbzc"]=xszd.jxbzc;
		requestMap["xszd.zyfx"]=xszd.zyfx;
		requestMap["xszd.jxbrs"]=xszd.jxbrs;
		requestMap["xszd.xkrs"]=xszd.xkrs;
		requestMap["xszd.kcxzjc"]=xszd.kcxzjc;
		requestMap["xszd.khfs"]=xszd.khfs;
		requestMap["xszd.ksfs"]=xszd.ksfs;
		requestMap["xszd.xkbz"]=xszd.xkbz;//选课备注
		requestMap["xszd.kcxszc"]=xszd.kcxszc;//课程学时组成
		requestMap["xszd.zhxs"]=xszd.zhxs;//周学时
		requestMap["xszd.zxs"]=xszd.zxs;
		requestMap["xszd.xf"]=xszd.xf;
		requestMap["xszd.zzrl"]=xszd.zzrl;
		requestMap["xszd.xq"]=xszd.xq;
		requestMap["xszd.zxxx"]=xszd.zxxx;
		requestMap["xszd.skfsmc"]=xszd.skfsmc;
		requestMap["xszd.zfj"]=xszd.zfj;
		requestMap["xszd.kczxs"]=xszd.kczxs;
		requestMap["xszd.zyhxkcbj"]=xszd.zyhxkcbj;
		requestMap["xszd.cdlbmc"]=xszd.cdlbmc;
		requestMap["xszd.ktmc"]=xszd.ktmc;
		requestMap["xszd.skpthyh"]=xszd.skpthyh;
		requestMap["xszd.jtskwz"]=xszd.jtskwz;
		requestMap["xszd.qqqh"]=xszd.qqqh;
		requestMap["xszd.zzmm"]=xszd.zzmm;
		requestMap["xszd.jgh"]=xszd.jgh;
		requestMap["xszd.kclbmc"]=xszd.kclbmc;
		requestMap["xnm"]= map.xnm;
		requestMap["xqm"]= map.xqm;
		requestMap["modelList[0].xnm"]= map.xnm;
		requestMap["modelList[0].xqm"]= map.xqm;
		requestMap["modelList[0].xnmc"]= map.xnmc;
		requestMap["modelList[0].xqmmc"]= map.xqmmc;
		requestMap["modelList[0].njdm_id"]= map.njdm_id||'';
		requestMap["modelList[0].zyh_id"]= map.zyh_id||'';
		requestMap["modelList[0].bh_id"]= map.bh_id||'';
		requestMap["modelList[0].xqh_id"]=map.xqh_id||'';
		requestMap["modelList[0].tjkbzdm"]= typeof(map.tjkbzdm)=='undefined'?"":map.tjkbzdm;
		requestMap["modelList[0].tjkbzxsdm"]= typeof(map.tjkbzxsdm)=='undefined'?"":map.tjkbzxsdm;
		requestMap["modelList[0].zxszjjs"] = typeof(map.zxszjjs)=='undefined'?"":map.zxszjjs;
		requestMap["modelList[0].akcxqjchb"] = typeof(map.akcxqjchb)=='undefined'?"":map.akcxqjchb;
		requestMap["modelList[0].tjkbmc"]=tjkbmc||'';
		requestMap["modelList[0].zymc"]=map.zymc||'';
		requestMap["modelList[0].jgmc"]=map.jgmc||'';
		requestMap["modelList[0].njmc"]=map.njmc||'';
		requestMap["modelList[0].bjmc"]= map.bj||'';
		requestMap["modelList[0].xkrs"]=map.xkrs||'';
		requestMap["modelList[0].jsxm"]= map.jsxm||'';
		requestMap["modelList[0].lxdh"]=map.lxdh||'';
		requestMap["modelList[0].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList[0].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList[0].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList[0].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList[0].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList[0].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList[0].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
		requestMap["kzlx"] ="dy";
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: requestMap,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
						if($('#shcPDF').attr('data-type')=='table'){
							//构建form
							if($("#exceltopdf").val()=="1" || $("#xxdm").val()=="10518"|| $("#xxdm").val()=='14428' ||"10475"==$("#xxdm").val()){
								$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb.html?exceltopdf=1",requestMap).submit();
		                	}else{
		                		$.buildForm("downForm",_path+"/kbdy/bjkbdy_shcpdfBjkb.html?doType=table",requestMap).submit();
		                	}
						}else{
							//构建form
							$.buildForm("downForm",_path+"/kbdy/bjkbdy_shcpdfBjkb.html?doType=list",requestMap).submit();
						}
				   }
			   }
			});

	});
	
	//输出Excel
	$('#shcExcel').click(function(){
		var requestMap = {};
		requestMap["xszd.kch"]=xszd.kch;
		requestMap["xszd.sj"]=xszd.sj;
		requestMap["xszd.cd"]=xszd.cd;
	    requestMap["xszd.js"]=xszd.js;
		requestMap["xszd.jszc"]=xszd.jszc;
		requestMap["xszd.jxb"]=xszd.jxb;
		requestMap["xszd.jxbzc"]=xszd.jxbzc;
		requestMap["xszd.zyfx"]=xszd.zyfx;
		requestMap["xszd.jxbrs"]=xszd.jxbrs;
		requestMap["xszd.xkrs"]=xszd.xkrs;
		requestMap["xszd.kcxzjc"]=xszd.kcxzjc;
		requestMap["xszd.khfs"]=xszd.khfs;
		requestMap["xszd.ksfs"]=xszd.ksfs;
		requestMap["xszd.xkbz"]=xszd.xkbz;//选课备注
		requestMap["xszd.kcxszc"]=xszd.kcxszc;//课程学时组成
		requestMap["xszd.zhxs"]=xszd.zhxs;//周学时
		requestMap["xszd.zxs"]=xszd.zxs;
		requestMap["xszd.xf"]=xszd.xf;
		requestMap["xszd.zzrl"]=xszd.zzrl;
		requestMap["xszd.xq"]=xszd.xq;
		requestMap["xszd.zxxx"]=xszd.zxxx;
		requestMap["xszd.skfsmc"]=xszd.skfsmc;
		requestMap["xszd.zfj"]=xszd.zfj;
		requestMap["xszd.kczxs"]=xszd.kczxs;
		requestMap["xszd.zyhxkcbj"]=xszd.zyhxkcbj;
		requestMap["xszd.cdlbmc"]=xszd.cdlbmc;
		requestMap["xszd.ktmc"]=xszd.ktmc;
		requestMap["xszd.skpthyh"]=xszd.skpthyh;
		requestMap["xszd.jtskwz"]=xszd.jtskwz;
		requestMap["xszd.qqqh"]=xszd.qqqh;
		requestMap["xszd.zzmm"]=xszd.zzmm;
		requestMap["xszd.jgh"]=xszd.jgh;
		requestMap["xszd.kclbmc"]=xszd.kclbmc;
		requestMap["xnm"]= map.xnm;
		requestMap["xqm"]= map.xqm;
		requestMap["xqh_id"]= map.xqh_id;
		requestMap["xnmc"]= map.xnmc;
		requestMap["xqmmc"]= map.xqmmc;debugger
		requestMap["modelList[0].xnm"]= map.xnm;
		requestMap["modelList[0].xqm"]= map.xqm;
		requestMap["modelList[0].xnmc"]= map.xnmc;
		requestMap["modelList[0].xqmmc"]= map.xqmmc;
		requestMap["modelList[0].njdm_id"]= map.njdm_id||'';
		requestMap["modelList[0].zyh_id"]= map.zyh_id||'';
		requestMap["modelList[0].bh_id"]= map.bh_id||'';
		requestMap["modelList[0].xqh_id"]=map.xqh_id||'';
		requestMap["modelList[0].tjkbzdm"]= typeof(map.tjkbzdm)=='undefined'?"":map.tjkbzdm;
		requestMap["modelList[0].tjkbzxsdm"]= typeof(map.tjkbzxsdm)=='undefined'?"":map.tjkbzxsdm;
		requestMap["modelList[0].zxszjjs"] = typeof(map.zxszjjs)=='undefined'?"":map.zxszjjs;
		requestMap["modelList[0].akcxqjchb"] = typeof(map.akcxqjchb)=='undefined'?"":map.akcxqjchb;
		requestMap["modelList[0].tjkbmc"]=tjkbmc||'';
		requestMap["modelList[0].bj"]= map.bj||'';
		requestMap["modelList[0].zymc"]=map.zymc||'';
		requestMap["modelList[0].jgmc"]=map.jgmc||'';
		requestMap["modelList[0].njmc"]=map.njmc||'';
		requestMap["modelList[0].bjmc"]= map.bj||'';
		requestMap["modelList[0].xkrs"]=map.xkrs||'';
		requestMap["modelList[0].jsxm"]= map.jsxm||'';
		requestMap["modelList[0].lxdh"]=map.lxdh||'';
		requestMap["modelList[0].bh"]=map.bh||'';
		requestMap["modelList[0].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList[0].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList[0].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList[0].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList[0].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList[0].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList[0].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
		requestMap["kzlx"] ="dy";
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: requestMap,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
					   if($("#xxdm").val()=="13965"){
						   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb13965.html",requestMap).submit();
					   }else{  
							if($('button[data-toggle="tab"].btn-primary').attr('data-type')=='table'){
								if($("#xxdm").val()=='13754'){
		                    		$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb13754.html",requestMap).submit();
		                    	}else{
		                    		$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb.html",requestMap).submit();
		                    	}
							}else{
								$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkblb.html",requestMap).submit();
							}	
						   
					   }  
				   }
			   }
			});
		//构建form
		//$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb.html",requestMap).submit();
		
	});
	
	//导出excel(纵向)
	$('#shcExcelzx_btn').click(function(){
		var requestMap = {};
		requestMap["xszd.kch"]=xszd.kch;
		requestMap["xszd.sj"]=xszd.sj;
		requestMap["xszd.cd"]=xszd.cd;
	    requestMap["xszd.js"]=xszd.js;
		requestMap["xszd.jszc"]=xszd.jszc;
		requestMap["xszd.jxb"]=xszd.jxb;
		requestMap["xszd.jxbzc"]=xszd.jxbzc;
		requestMap["xszd.zyfx"]=xszd.zyfx;
		requestMap["xszd.jxbrs"]=xszd.jxbrs;
		requestMap["xszd.xkrs"]=xszd.xkrs;
		requestMap["xszd.kcxzjc"]=xszd.kcxzjc;
		requestMap["xszd.khfs"]=xszd.khfs;
		requestMap["xszd.ksfs"]=xszd.ksfs;
		requestMap["xszd.xkbz"]=xszd.xkbz;//选课备注
		requestMap["xszd.kcxszc"]=xszd.kcxszc;//课程学时组成
		requestMap["xszd.zhxs"]=xszd.zhxs;//周学时
		requestMap["xszd.zxs"]=xszd.zxs;
		requestMap["xszd.xf"]=xszd.xf;
		requestMap["xszd.zzrl"]=xszd.zzrl;
		requestMap["xszd.xq"]=xszd.xq;
		requestMap["xszd.zxxx"]=xszd.zxxx;
		requestMap["xszd.skfsmc"]=xszd.skfsmc;
		requestMap["xszd.zfj"]=xszd.zfj;
		requestMap["xszd.kczxs"]=xszd.kczxs;
		requestMap["xszd.zzmm"]=xszd.zzmm;
		requestMap["xszd.jgh"]=xszd.jgh;
		requestMap["xszd.kclbmc"]=xszd.kclbmc;
		requestMap["xnm"]= map.xnm;
		requestMap["xqm"]= map.xqm;
		requestMap["xqh_id"]= map.xqh_id;
		requestMap["xnmc"]= map.xnmc;
		requestMap["xqmmc"]= map.xqmmc;
		requestMap["modelList[0].xnm"]= map.xnm;
		requestMap["modelList[0].xqm"]= map.xqm;
		requestMap["modelList[0].xnmc"]= map.xnmc;
		requestMap["modelList[0].xqmmc"]= map.xqmmc;
		requestMap["modelList[0].njdm_id"]= map.njdm_id||'';
		requestMap["modelList[0].zyh_id"]= map.zyh_id||'';
		requestMap["modelList[0].bh_id"]= map.bh_id||'';
		requestMap["modelList[0].xqh_id"]=map.xqh_id||'';
		requestMap["modelList[0].tjkbzdm"]= typeof(map.tjkbzdm)=='undefined'?"":map.tjkbzdm;
		requestMap["modelList[0].tjkbzxsdm"]= typeof(map.tjkbzxsdm)=='undefined'?"":map.tjkbzxsdm;
		requestMap["modelList[0].zxszjjs"] = typeof(map.zxszjjs)=='undefined'?"":map.zxszjjs;
		requestMap["modelList[0].akcxqjchb"] = typeof(map.akcxqjchb)=='undefined'?"":map.akcxqjchb;
		requestMap["modelList[0].tjkbmc"]=tjkbmc||'';
		requestMap["modelList[0].bj"]= map.bj||'';
		requestMap["modelList[0].zymc"]=map.zymc||'';
		requestMap["modelList[0].jgmc"]=map.jgmc||'';
		requestMap["modelList[0].bjmc"]= map.bj||'';
		requestMap["modelList[0].njmc"]=map.njmc||'';
		requestMap["modelList[0].xkrs"]=map.xkrs||'';
		requestMap["modelList[0].jsxm"]= map.jsxm||'';
		requestMap["modelList[0].lxdh"]=map.lxdh||'';
		requestMap["modelList[0].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList[0].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList[0].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList[0].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList[0].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList[0].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList[0].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
		requestMap["kzlx"] ="dy";
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: requestMap,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
					   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelzxBjkb.html",requestMap).submit();  
				   }
			   }
		});
		
	});
	
	
	$('#shcPdfzx_btn').click(function(){
		var requestMap = {};
		requestMap["xszd.kch"]=xszd.kch;
		requestMap["xszd.sj"]=xszd.sj;
		requestMap["xszd.cd"]=xszd.cd;
	    requestMap["xszd.js"]=xszd.js;
		requestMap["xszd.jszc"]=xszd.jszc;
		requestMap["xszd.jxb"]=xszd.jxb;
		requestMap["xszd.jxbzc"]=xszd.jxbzc;
		requestMap["xszd.zyfx"]=xszd.zyfx;
		requestMap["xszd.jxbrs"]=xszd.jxbrs;
		requestMap["xszd.xkrs"]=xszd.xkrs;
		requestMap["xszd.kcxzjc"]=xszd.kcxzjc;
		requestMap["xszd.khfs"]=xszd.khfs;
		requestMap["xszd.ksfs"]=xszd.ksfs;
		requestMap["xszd.xkbz"]=xszd.xkbz;//选课备注
		requestMap["xszd.kcxszc"]=xszd.kcxszc;//课程学时组成
		requestMap["xszd.zhxs"]=xszd.zhxs;//周学时
		requestMap["xszd.zxs"]=xszd.zxs;
		requestMap["xszd.xf"]=xszd.xf;
		requestMap["xszd.zzrl"]=xszd.zzrl;
		requestMap["xszd.xq"]=xszd.xq;
		requestMap["xszd.zxxx"]=xszd.zxxx;
		requestMap["xszd.skfsmc"]=xszd.skfsmc;
		requestMap["xszd.zfj"]=xszd.zfj;
		requestMap["xszd.kczxs"]=xszd.kczxs;
		requestMap["xszd.zzmm"]=xszd.zzmm;
		requestMap["xszd.jgh"]=xszd.jgh;
		requestMap["xszd.kclbmc"]=xszd.kclbmc;
		requestMap["xnm"]= map.xnm;
		requestMap["xqm"]= map.xqm;
		requestMap["xqh_id"]= map.xqh_id;
		requestMap["xnmc"]= map.xnmc;
		requestMap["xqmmc"]= map.xqmmc;
		requestMap["modelList[0].xnm"]= map.xnm;
		requestMap["modelList[0].xqm"]= map.xqm;
		requestMap["modelList[0].xnmc"]= map.xnmc;
		requestMap["modelList[0].xqmmc"]= map.xqmmc;
		requestMap["modelList[0].njdm_id"]= map.njdm_id||'';
		requestMap["modelList[0].zyh_id"]= map.zyh_id||'';
		requestMap["modelList[0].bh_id"]= map.bh_id||'';
		requestMap["modelList[0].xqh_id"]=map.xqh_id||'';
		requestMap["modelList[0].tjkbzdm"]= typeof(map.tjkbzdm)=='undefined'?"":map.tjkbzdm;
		requestMap["modelList[0].tjkbzxsdm"]= typeof(map.tjkbzxsdm)=='undefined'?"":map.tjkbzxsdm;
		requestMap["modelList[0].zxszjjs"] = typeof(map.zxszjjs)=='undefined'?"":map.zxszjjs;
		requestMap["modelList[0].akcxqjchb"] = typeof(map.akcxqjchb)=='undefined'?"":map.akcxqjchb;
		requestMap["modelList[0].tjkbmc"]=tjkbmc||'';
		requestMap["modelList[0].bj"]= map.bj||'';
		requestMap["modelList[0].zymc"]=map.zymc||'';
		requestMap["modelList[0].jgmc"]=map.jgmc||'';
		requestMap["modelList[0].bjmc"]= map.bj||'';
		requestMap["modelList[0].njmc"]=map.njmc||'';
		requestMap["modelList[0].xkrs"]=map.xkrs||'';
		requestMap["modelList[0].jsxm"]= map.jsxm||'';
		requestMap["modelList[0].lxdh"]=map.lxdh||'';
		requestMap["modelList[0].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList[0].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList[0].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList[0].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList[0].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList[0].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList[0].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
		requestMap["kzlx"] ="dy";
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: requestMap,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
					   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb.html?exceltopdf=1",requestMap).submit(); 
				   }
			   }
		});
		
	});
	
	
	
	$('#shcPDFGz').click(function(){
		var requestMap = {};
		requestMap["xszd.kch"]=xszd.kch;
		requestMap["xszd.sj"]=xszd.sj;
		requestMap["xszd.cd"]=xszd.cd;
	    requestMap["xszd.js"]=xszd.js;
		requestMap["xszd.jszc"]=xszd.jszc;
		requestMap["xszd.jxb"]=xszd.jxb;
		requestMap["xszd.jxbzc"]=xszd.jxbzc;
		requestMap["xszd.zyfx"]=xszd.zyfx;
		requestMap["xszd.jxbrs"]=xszd.jxbrs;
		requestMap["xszd.xkrs"]=xszd.xkrs;
		requestMap["xszd.kcxzjc"]=xszd.kcxzjc;
		requestMap["xszd.khfs"]=xszd.khfs;
		requestMap["xszd.ksfs"]=xszd.ksfs;
		requestMap["xszd.xkbz"]=xszd.xkbz;//选课备注
		requestMap["xszd.kcxszc"]=xszd.kcxszc;//课程学时组成
		requestMap["xszd.zhxs"]=xszd.zhxs;//周学时
		requestMap["xszd.zxs"]=xszd.zxs;
		requestMap["xszd.xf"]=xszd.xf;
		requestMap["xszd.zzrl"]=xszd.zzrl;
		requestMap["xszd.xq"]=xszd.xq;
		requestMap["xszd.zxxx"]=xszd.zxxx;
		requestMap["xszd.skfsmc"]=xszd.skfsmc;
		requestMap["xszd.zfj"]=xszd.zfj;
		requestMap["xszd.kczxs"]=xszd.kczxs;
		requestMap["xszd.zyhxkcbj"]=xszd.zyhxkcbj;
		requestMap["xszd.cdlbmc"]=xszd.cdlbmc;
		requestMap["xszd.ktmc"]=xszd.ktmc;
		requestMap["xszd.skpthyh"]=xszd.skpthyh;
		requestMap["xszd.jtskwz"]=xszd.jtskwz;
		requestMap["xszd.qqqh"]=xszd.qqqh;
		requestMap["xszd.zzmm"]=xszd.zzmm;
		requestMap["xszd.jgh"]=xszd.jgh;
		requestMap["xszd.kclbmc"]=xszd.kclbmc;
		requestMap["xnm"]= map.xnm;
		requestMap["xqm"]= map.xqm;
		requestMap["modelList[0].xnm"]= map.xnm;
		requestMap["modelList[0].xqm"]= map.xqm;
		requestMap["modelList[0].xnmc"]= map.xnmc;
		requestMap["modelList[0].xqmmc"]= map.xqmmc;
		requestMap["modelList[0].njdm_id"]= map.njdm_id||'';
		requestMap["modelList[0].zyh_id"]= map.zyh_id||'';
		requestMap["modelList[0].bh_id"]= map.bh_id||'';
		requestMap["modelList[0].xqh_id"]=map.xqh_id||'';
		requestMap["modelList[0].tjkbzdm"]= typeof(map.tjkbzdm)=='undefined'?"":map.tjkbzdm;
		requestMap["modelList[0].tjkbzxsdm"]= typeof(map.tjkbzxsdm)=='undefined'?"":map.tjkbzxsdm;
		requestMap["modelList[0].zxszjjs"] = typeof(map.zxszjjs)=='undefined'?"":map.zxszjjs;
		requestMap["modelList[0].akcxqjchb"] = typeof(map.akcxqjchb)=='undefined'?"":map.akcxqjchb;
		requestMap["modelList[0].tjkbmc"]=tjkbmc||'';
		requestMap["modelList[0].zymc"]=map.zymc||'';
		requestMap["modelList[0].jgmc"]=map.jgmc||'';
		requestMap["modelList[0].njmc"]=map.njmc||'';
		requestMap["modelList[0].bjmc"]= map.bj||'';
		requestMap["modelList[0].xkrs"]=map.xkrs||'';
		requestMap["modelList[0].jsxm"]= map.jsxm||'';
		requestMap["modelList[0].lxdh"]=map.lxdh||'';
		requestMap["modelList[0].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList[0].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList[0].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList[0].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList[0].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList[0].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList[0].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
		requestMap["kzlx"] ="dy";
		requestMap["gsbm"] = "14065-zw-bjkb";
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: requestMap,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
						
						$.post(_path + '/kbdy/bjkbdy_plscgzpdfBjkb.html?doType=table', requestMap, function (filePath) {
	                        if (filePath.indexOf("成功") > -1) {
	                            if (isIE()) {
	                                checkIEhasPdfReader();
	                                window.open(filePath.split("#")[0]);
	                            } else {
	                                window.open(filePath.split("#")[0]);
	                            }
	                        } else {
	                            $.alert(filePath);
	                        }
	                    });
				   }
			   }
			});

	});

	function isIE(){  //判断是否ie浏览器，ie浏览器直接打开,非ie浏览器调用pdf.js插件
        return /msie/.test(navigator.userAgent.toLowerCase());
    };

    function checkIEhasPdfReader(){
        var bool = false;
        if(hasReader()||hasReaderActiveX()){
            bool = true;
        }else{
            $.error($.i18n.jwglxt["wjcdadobe"],function() {
            });
        }
        return bool;
    };
    
    
});

