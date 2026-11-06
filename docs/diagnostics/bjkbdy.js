//显示字段
var xszd = {};
var tjkbmc_list = [];

var grid = $.extend(true,{},BaseJqGrid,{
	resizeHandle:"#grid_div",
	rownumbers : true,
    //height: 330,
    shrinkToFit: true,
    pager:"#pager",
    datatype: "local",
    colModel:[
		{label: 'id', name: 'id', hidden: true, key: true},
		{label: $.i18n.get("tjkbzdm"), name: 'tjkbzdm', hidden: true},
		{label: $.i18n.get("tjkbzxsdm"), name: 'tjkbzxsdm', hidden: true},
		{label: $.i18n.get("xnm"), name: 'xnm', hidden: true},
		{label: $.i18n.get("xqm"), name: 'xqm', hidden: true},
		{label: $.i18n.get("xnmmc"), name: 'xnmc', hidden: true},
		{label: $.i18n.get("xqmmc"), name: 'xqmmc', hidden: true},
		{label: $.i18n.get("xqh"), name: 'xqh_id', hidden: true},
		{label: $.i18n.get("njdm"), name: 'njdm_id', hidden: true},
		{label: $.i18n.get("zydm"), name: 'zyh_id', hidden: true},
		{label: $.i18n.get("bjdmid"), name: 'bh_id', hidden: true},
		{label: $.i18n.get("zxrs"), name: 'xkrs', hidden: true},
		{label: $.i18n.get("jsxm"), name: 'jsxm', width: 90, index: 'jsxm', hidden: true},
		{label: $.i18n.get("njmc"), name: 'njmc', width: 90, index: 'njmc', hidden: true},
		{label: $.i18n.get("xqmc"), name: 'xqmc', width: 90, index: 'xqmc', hidden: true},
		{label: $.i18n.get("lxdh"), name: 'lxdh', width: 90, index: 'lxdh', hidden: true},
		{label: $.i18n.get("kbmc"), name: 'tjkbmc', width: 90, index: 'tjkbmc', formatter: ylFmt},
		// {label:$.i18n.get("xbxx"),name:'xbxx', width: 90 ,index: 'xbxx',hidden:$("#sfaxbgljg").val()!='1'},
		{label:'学部',name:'xbxx', width: 90 ,index: 'xbxx',hidden:$("#sfaxbgljg").val()!='1'},
		{label: $.i18n.get("xy"), name: 'jgmc', width: 90, index: 'jgmc'},
		{label: $.i18n.get("nj"), name: 'njmc', width: 90, index: 'njmc'},
		{label: $.i18n.get("zy"), name: 'zymc', width: 90, index: 'zymc'},
		{label: $.i18n.get("cc"), name: 'pyccmc', width: 90, index: 'pyccmc',hidden:$("#xxdm").val()=='10335'},
		{label: $.i18n.get("bjdm"), name: 'bh', width: 90, index: 'bh', hidden: true},
		{label: $.i18n.get("bj"), name: 'bj', width: 90, index: 'bj',hidden:$("#xxdm").val()=='10335'},
		{label: 'jgdm', name: 'jgdm', width: 90, index: 'jgdm', hidden: true},
		{label: 'zyh', name: 'zyh', width: 90, index: 'zyh', hidden: true},
		{label: 'njdm', name: 'njdm', width: 90, index: 'njdm', hidden: true},
		{label: 'xsxyxh', name: 'xsxyxh', width: 90, index: 'xsxyxh', hidden: true},
	],
	
	onSelectRow: function(rowid,status){ 
		if(!($("#xxdm").val()=='10335' && $("#jsdm").val()=='xs')){
			selectEvent(rowid,status);
		}
		
	},
	onSelectAll:function(aRowids,status){
		if(!($("#xxdm").val()=='10335' && $("#jsdm").val()=='xs')){
			$.each(aRowids,function(index,rowid){
				selectEvent(rowid,status);
			});
		}
	},
	loadComplete:function(){
		$.each(tjkbmc_list, function(i,n){
			$("#tabGrid").setSelection(n);
		});
	},
	/*sortname: 'tjkbmc', //首次加载要进行排序的字段 
 	sortorder: 'asc'*/
});

//选中事件
function selectEvent(rowid, status){
	var rowData = $("#tabGrid").getRowData(rowid);
	if(status == true){
		//if($("#bot_ul").find("li[name='"+rowid+"']").size()==0){
		if($.inArray(rowid, tjkbmc_list) == -1){
			tjkbmc_list.push(rowid); //存入变量
			$("#bot_ul").append("<li name="+rowid+" data='" + rowData.tjkbzdm +"~"+ 
					rowData.tjkbzxsdm +"~"+ rowData.xnm +"~"+ rowData.xqm +"~"+ rowData.xnmc +"~"+ 
					rowData.xqmmc  +"~"+ rowData.njdm_id +"~"+ rowData.zyh_id +"~"+
					rowData.bh_id +"~"+ $(rowData.tjkbmc).text()+"~"+ rowData.xqh_id +"~"+ rowData.zymc+"~"+ rowData.jgmc+"~"+ rowData.bj+"~"+ rowData.xkrs
					+"~"+ rowData.jsxm+"~"+ rowData.lxdh+"~"+ rowData.xqmc+"~"+ rowData.bh+"~"+ rowData.njmc+"~"+ rowData.jgdm+"~"+ rowData.zyh+"~"+ rowData.njdm+"~"+ rowData.xsxyxh+"'>"+
					"<a href='#'>"+$(rowData.tjkbmc).text()+"</a><a href='#' onclick='removeSelect(\""+rowid+"\")'><span></span></a></li>");
		}
	}else{
		tjkbmc_list.splice($.inArray(rowid, tjkbmc_list) ,1); //从变量中删除
		$("#bot_ul").find("li[name='"+rowid+"']").remove();
	}
	
	if(tjkbmc_list.length > 0){
		$(".sl_fixbt_pop").show();
	}else{
		$(".sl_fixbt_pop").hide();
	}
}

function removeSelect(rowid){
	if($("#tabGrid").getRowData(rowid).id){
		$("#tabGrid").setSelection(rowid);
	}else{
		tjkbmc_list.splice($.inArray(rowid, tjkbmc_list) ,1); //从变量中删除
		$("#bot_ul").find("li[name='"+rowid+"']").remove();
	}
	
	if(tjkbmc_list.length == 0){
		$(".sl_fixbt_pop").hide();
	}
}

function ylFmt(cellvalue, options, rowObject){
	return "<a href='#' style='cursor: pointer; text-decoration:none' onclick='ylKbdy(\""+options.rowId+"\")'>"+cellvalue+"</a>"
}

function paramMap(){
	var map = {};
	map["xnm"] = $('#xnm').val();
	map["xqm"] = $('#xqm').val();
	map["xqh_id"] = $('#xqh_id').val();
	map["njdm_id"] = $('#njdm_id').val();
	map["xb_id"]=$.founded($("#xb_id").val())?$("#xb_id").val():"";
	map["jg_id"] = $('#jg_id').val();
	map["zyh_id"] = $('#zyh_id').val();
	map["zyfx_id"] = $("#zyfx_id").val()||'';
	map["bh_id"] = $('#bh_id').val()||'';
	map["xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
	map["pyccdm"] = $("#pyccdm").val()||'';
	map["kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
	map["kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
	map["sfzhsjk"] = $("#sfzhsjk").val()||'';
	map["kbsjlyqz"] = $("#kbsjlyqz").val()||'';
	map["kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
	map["zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
	map["yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
	map["sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
	return map;
}

function ylKbdy(rowId){
	var rowObject = $("#tabGrid").getRowData(rowId);
	var map = {};
	map["xnm"] = rowObject.xnm;
	map["xqm"] = rowObject.xqm;
	map["xnmc"] = rowObject.xnmc;
	map["xqmmc"] = rowObject.xqmmc;
	map["xqh_id"] = rowObject.xqh_id;
	map["njdm_id"] = rowObject.njdm_id;
	map["zyh_id"] = rowObject.zyh_id;
	map["bh_id"] = rowObject.bh_id;
	map["tjkbzdm"] = rowObject.tjkbzdm;
	map["tjkbzxsdm"] = rowObject.tjkbzxsdm;
	map["zymc"] = rowObject.zymc;
	map["jgmc"] = rowObject.jgmc;
	map["njmc"] = rowObject.njmc;
	map["bj"] = rowObject.bj;
	map["xkrs"] = rowObject.xkrs;
	map["jsxm"] = rowObject.jsxm;
	map["lxdh"] = rowObject.lxdh;
	map["bh"] = rowObject.bh;
	map["zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
	map["zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
	map["akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
	map["xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
	map["kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
	map["kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
	map["kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
	map["yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
	map["kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
	map["kzlx"] ="ck";
	map["sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
	$.ajax({
		   type: "POST",
		   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
		   async: false,
		   data: map,
		   datatype: "json",
		   success: function(data){
			   if(data.indexOf("true") > -1){
				   $.alert($.i18n.get("alert1"));
					flag=true;
			   }else{
				   $.showDialog(_path+'/kbdy/bjkbdy_ylBjkbdyIndex.html',$.i18n.get("alert2"),$.extend({},viewConfig,
							{width: $("#yhgnPage").innerWidth()+"px", data: {'map':map, 'tjkbmc':$(rowObject.tjkbmc).text()}}
					));
			   }
		   }
		});
//		$.showDialog(_path+'/kbdy/bjkbdy_ylBjkbdyIndex.html','课表预览',$.extend({},viewConfig,
//				{width: $("#yhgnPage").innerWidth()+"px", data: {'map':map, 'tjkbmc':$(rowObject.tjkbmc).text()}}
//		));
	
}

//查询
function searchResult(){
	if($("#ajaxForm").validateForm("isValid")){
	    $("#tabGrid").setGridParam({ 
			datatype: "json",
			loadonce: false,
			url: _path+'/kbdy/bjkbdy_cxBjkbdyTjkbList.html', 
			postData : paramMap(),
			page:1
		}).trigger('reloadGrid');
	}
}

function batchShcPdfGz(type){
	//var selrows = $("#tabGrid").getGridParam("selarrrow");
	if($("#bot_ul").children().length == 0){
		$.alert($.i18n.get("alert6"));
		return false;
	}
	var requestMap = {
		"xszd.sj" 	: xszd.sj,
		"xszd.cd" 	: xszd.cd,
		"xszd.js" 	: xszd.js,
		"xszd.jszc" : xszd.jszc,
		"xszd.jxb" 	: xszd.jxb,
		"xszd.jxbzc" 	: xszd.jxbzc,
		"xszd.kcxzjc" 	: xszd.kcxzjc,
		"xszd.jxbrs" 	: xszd.jxbrs,
		"xszd.zyfx" : xszd.zyfx,
		"xszd.xkrs" : xszd.xkrs,
		"xszd.khfs" : xszd.khfs,
		"xszd.ksfs" : xszd.ksfs,
		"xszd.xkbz" : xszd.xkbz,
		"xszd.kcxszc" : xszd.kcxszc,
		"xszd.zhxs" : xszd.zhxs,
		"xszd.zxs" : xszd.zxs,
		"xszd.xf"  : xszd.xf,
		"xszd.zzrl"  : xszd.zzrl,
		"xszd.xq"  : xszd.xq,
		"xszd.skfsmc"  : xszd.skfsmc,
		"xszd.zxxx"  : xszd.zxxx,
		"xszd.zfj"  : xszd.zfj,
		"xszd.kch"  : xszd.kch,
		"xszd.kczxs":xszd.kczxs,
		"xszd.zyhxkcbj":xszd.zyhxkcbj,
		"xszd.cdlbmc":xszd.cdlbmc,
		"xszd.zzmm":xszd.zzmm,
		"xszd.ktmc":xszd.ktmc,
		"xszd.skpthyh":xszd.skpthyh,
		"xszd.jtskwz":xszd.jtskwz,
		"xszd.qqqh":xszd.qqqh,
		"xszd.jgh":xszd.jgh,
		"xszd.kclbmc":xszd.kclbmc,
		"gsbm":"14065-zw-bjkb"
	};
	$("#bot_ul").children().each(function(index, item){
		var datas = $(this).attr("data");
		var data = datas.split("~");
		if(index == 0){
			requestMap["xnm"] = data[2];
			requestMap["xqm"] = data[3];
		}		
		requestMap["modelList["+index+"].tjkbzdm"] = data[0];
		requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
		requestMap["modelList["+index+"].xnm"] = data[2];
		requestMap["modelList["+index+"].xqm"] = data[3];
		requestMap["modelList["+index+"].xnmc"] = data[4];
		requestMap["modelList["+index+"].xqmmc"] = data[5];
		requestMap["modelList["+index+"].njdm_id"] =data[6];
		requestMap["modelList["+index+"].zyh_id"] = data[7];
		requestMap["modelList["+index+"].bh_id"] = data[8];
		requestMap["modelList["+index+"].tjkbmc"] = data[9];
		requestMap["modelList["+index+"].xqh_id"] = data[10];
		requestMap["modelList["+index+"].zymc"] = data[11];
		requestMap["modelList["+index+"].jgmc"] = data[12];
		requestMap["modelList["+index+"].bjmc"] = data[13];
		requestMap["modelList["+index+"].xkrs"] = data[14];
		requestMap["modelList["+index+"].jsxm"] = data[15];
		requestMap["modelList["+index+"].lxdh"] = data[16];
		requestMap["modelList["+index+"].bh"] = data[17];
		requestMap["modelList["+index+"].njmc"] = data[19];
		requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList["+index+"].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList["+index+"].zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
		requestMap["modelList["+index+"].akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
		requestMap["modelList["+index+"].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
	});
	var map = {};
	map["kzlx"] ="dy";
	map["xnm"] =requestMap["modelList[0].xnm"];
	map["xqm"] =requestMap["modelList[0].xqm"];
	$.ajax({
		   type: "POST",
		   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
		   async: false,
		   data: map,
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
}
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
//批量输出pdf
function batchShcPdf(type){
	//var selrows = $("#tabGrid").getGridParam("selarrrow");
	if($("#bot_ul").children().length == 0){
		$.alert($.i18n.get("alert6"));
		return false;
	}
	var requestMap = {
		"xszd.sj" 	: xszd.sj,
		"xszd.cd" 	: xszd.cd,
		"xszd.js" 	: xszd.js,
		"xszd.jszc" : xszd.jszc,
		"xszd.jxb" 	: xszd.jxb,
		"xszd.jxbzc" 	: xszd.jxbzc,
		"xszd.kcxzjc" 	: xszd.kcxzjc,
		"xszd.jxbrs" 	: xszd.jxbrs,
		"xszd.zyfx" : xszd.zyfx,
		"xszd.xkrs" : xszd.xkrs,
		"xszd.khfs" : xszd.khfs,
		"xszd.ksfs" : xszd.ksfs,
		"xszd.xkbz" : xszd.xkbz,
		"xszd.kcxszc" : xszd.kcxszc,
		"xszd.zhxs" : xszd.zhxs,
		"xszd.zxs" : xszd.zxs,
		"xszd.xf"  : xszd.xf,
		"xszd.zzrl"  : xszd.zzrl,
		"xszd.xq"  : xszd.xq,
		"xszd.skfsmc"  : xszd.skfsmc,
		"xszd.zxxx"  : xszd.zxxx,
		"xszd.zfj"  : xszd.zfj,
		"xszd.kch"  : xszd.kch,
		"xszd.kczxs":xszd.kczxs,
		"xszd.zyhxkcbj":xszd.zyhxkcbj,
		"xszd.cdlbmc":xszd.cdlbmc,
		"xszd.ktmc":xszd.ktmc,
		"xszd.skpthyh":xszd.skpthyh,
		"xszd.jtskwz":xszd.jtskwz,
		"xszd.qqqh":xszd.qqqh,
		"xszd.jgh":xszd.jgh,
		"xszd.kclbmc":xszd.kclbmc,
		"xszd.zzmm":xszd.zzmm
	};
	$("#bot_ul").children().each(function(index, item){
		var datas = $(this).attr("data");
		var data = datas.split("~");
		if(index == 0){
			requestMap["xnm"] = data[2];
			requestMap["xqm"] = data[3];
		}		
		requestMap["modelList["+index+"].tjkbzdm"] = data[0];
		requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
		requestMap["modelList["+index+"].xnm"] = data[2];
		requestMap["modelList["+index+"].xqm"] = data[3];
		requestMap["modelList["+index+"].xnmc"] = data[4];
		requestMap["modelList["+index+"].xqmmc"] = data[5];
		requestMap["modelList["+index+"].njdm_id"] =data[6];
		requestMap["modelList["+index+"].zyh_id"] = data[7];
		requestMap["modelList["+index+"].bh_id"] = data[8];
		requestMap["modelList["+index+"].tjkbmc"] = data[9];
		requestMap["modelList["+index+"].xqh_id"] = data[10];
		requestMap["modelList["+index+"].zymc"] = data[11];
		requestMap["modelList["+index+"].jgmc"] = data[12];
		requestMap["modelList["+index+"].bjmc"] = data[13];
		requestMap["modelList["+index+"].xkrs"] = data[14];
		requestMap["modelList["+index+"].jsxm"] = data[15];
		requestMap["modelList["+index+"].lxdh"] = data[16];
		requestMap["modelList["+index+"].bh"] = data[18];
		requestMap["modelList["+index+"].njmc"] = data[19];
		requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList["+index+"].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList["+index+"].zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
		requestMap["modelList["+index+"].akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
		requestMap["modelList["+index+"].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
	});
	var map = {};
	map["kzlx"] ="dy";
	map["xnm"] =requestMap["modelList[0].xnm"];
	map["xqm"] =requestMap["modelList[0].xqm"];
	$.ajax({
		   type: "POST",
		   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
		   async: false,
		   data: map,
		   datatype: "json",
		   success: function(data){
			   if(data.indexOf("true") > -1){
				   $.alert($.i18n.get("alert4"));
					flag=true;
			   }else{
					if(type=='list'){
					//构建form
					$.buildForm("downForm",_path+"/kbdy/bjkbdy_shcpdfBjkb.html?doType=list",requestMap).submit();
				}else if(type=='table'){
					if($("#exceltopdf").val()=="1" || $("#xxdm").val() == '10518' || $("#xxdm").val()=='14428' ||"10475"==$("#xxdm").val()){
						$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb.html?exceltopdf=1",requestMap).submit();
                	}else{
						//构建form
						$.buildForm("downForm",_path+"/kbdy/bjkbdy_shcpdfBjkb.html?doType=table",requestMap).submit();
                	}
				}else{
					//构建form
					$.buildForm("downForm",_path+"/kbdy/bjkbdy_shcpdfBjkb.html?doType=both",requestMap).submit();
				}
			   }
		   }
		});
//	if(type=='list'){
//		//构建form
//		$.buildForm("downForm",_path+"/kbdy/bjkbdy_shcpdfBjkb.html?doType=list",requestMap).submit();
//	}else if(type=='table'){
//		//构建form
//		$.buildForm("downForm",_path+"/kbdy/bjkbdy_shcpdfBjkb.html?doType=table",requestMap).submit();
//	}else{
//		//构建form
//		$.buildForm("downForm",_path+"/kbdy/bjkbdy_shcpdfBjkb.html?doType=both",requestMap).submit();
//	}
}

//批量输出Excel(浙江警察)
$("#plhbsc_btn").on("click",function(){
	//var selrows = $("#tabGrid").getGridParam("selarrrow");
	if($("#bot_ul").children().length == 0){
		$.alert($.i18n.get("alert6"));
		return false;
	}
	var requestMap = {
		"xszd.sj" 	: xszd.sj,
		"xszd.cd" 	: xszd.cd,
		"xszd.js" 	: xszd.js,
		"xszd.jszc" : xszd.jszc,
		"xszd.jxb" 	: xszd.jxb,
		"xszd.jxbzc" 	: xszd.jxbzc,
		"xszd.kcxzjc" 	: xszd.kcxzjc,
		"xszd.jxbrs" 	: xszd.jxbrs,
		"xszd.zyfx" : xszd.zyfx,
		"xszd.xkrs" 	: xszd.xkrs,
		"xszd.khfs" : xszd.khfs,
		"xszd.ksfs" : xszd.ksfs,
		"xszd.xkbz" : xszd.xkbz,
		"xszd.kcxszc" : xszd.kcxszc,
		"xszd.zhxs" : xszd.zhxs,
		"xszd.zxs" : xszd.zxs,
		"xszd.xf"  : xszd.xf,
		"xszd.zzrl"  : xszd.zzrl,
		"xszd.skfsmc"  : xszd.skfsmc,
		"xszd.xq"  : xszd.xq,
		"xszd.kch"  : xszd.kch,
		"xszd.kczxs":xszd.kczxs,
		"xszd.zyhxkcbj":xszd.zyhxkcbj,
		"xszd.cdlbmc":xszd.cdlbmc,
		"xszd.ktmc":xszd.ktmc,
		"xszd.skpthyh":xszd.skpthyh,
		"xszd.jtskwz":xszd.jtskwz,
		"xszd.qqqh":xszd.qqqh,
		"xszd.jgh":xszd.jgh,
		"xszd.kclbmc":xszd.kclbmc,
		"xszd.zzmm":xszd.zzmm
	};
	requestMap["xqh_id"] = $("#xqh_id").val();
	$("#bot_ul").children().each(function(index, item){
		var datas = $(this).attr("data");
		var data = datas.split("~");
		
		if(index == 0){
			requestMap["xnm"] = data[2];
			requestMap["xqm"] = data[3];
			requestMap["xqh_id"] = data[10];
		}		
		requestMap["modelList["+index+"].tjkbzdm"] = data[0];
		requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
		requestMap["modelList["+index+"].xnm"] = data[2];
		requestMap["modelList["+index+"].xqm"] = data[3];
		requestMap["modelList["+index+"].xnmc"] = data[4];
		requestMap["modelList["+index+"].xqmmc"] = data[5];
		requestMap["modelList["+index+"].njdm_id"] =data[6];
		requestMap["modelList["+index+"].zyh_id"] = data[7];
		requestMap["modelList["+index+"].bh_id"] = data[8];
		requestMap["modelList["+index+"].tjkbmc"] = data[9];
		requestMap["modelList["+index+"].xqh_id"] = data[10];
		requestMap["modelList["+index+"].zymc"] = data[11];
		requestMap["modelList["+index+"].jgmc"] = data[12];
		requestMap["modelList["+index+"].bjmc"] = data[13];
		requestMap["modelList["+index+"].xkrs"] = data[14];
		requestMap["modelList["+index+"].jsxm"] = data[15];
		requestMap["modelList["+index+"].lxdh"] = data[16];
		requestMap["modelList["+index+"].xqmc"] = data[17];
		requestMap["modelList["+index+"].bh"] = data[18];
		requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList["+index+"].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList["+index+"].zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
		requestMap["modelList["+index+"].akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
		requestMap["modelList["+index+"].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
	});
	
	$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcZjjczykb.html",requestMap).submit();

});

//批量输出pdf(浙江警察)
$("#plhbscpdf_btn").on("click",function(){
	if($("#bot_ul").children().length == 0){
		$.alert($.i18n.get("alert6"));
		return false;
	}
	var requestMap = {
		"xszd.sj" 	: xszd.sj,
		"xszd.cd" 	: xszd.cd,
		"xszd.js" 	: xszd.js,
		"xszd.jszc" : xszd.jszc,
		"xszd.jxb" 	: xszd.jxb,
		"xszd.jxbzc" 	: xszd.jxbzc,
		"xszd.kcxzjc" 	: xszd.kcxzjc,
		"xszd.jxbrs" 	: xszd.jxbrs,
		"xszd.zyfx" : xszd.zyfx,
		"xszd.xkrs" : xszd.xkrs,
		"xszd.khfs" : xszd.khfs,
		"xszd.ksfs" : xszd.ksfs,
		"xszd.xkbz" : xszd.xkbz,
		"xszd.kcxszc" : xszd.kcxszc,
		"xszd.zhxs" : xszd.zhxs,
		"xszd.zxs" : xszd.zxs,
		"xszd.xf"  : xszd.xf,
		"xszd.zzrl"  : xszd.zzrl,
		"xszd.skfsmc"  : xszd.skfsmc,
		"xszd.xq"  : xszd.xq,
		"xszd.kch"  : xszd.kch,
		"xszd.kczxs":xszd.kczxs,
		"xszd.zyhxkcbj":xszd.zyhxkcbj,
		"xszd.cdlbmc":xszd.cdlbmc,
		"xszd.ktmc":xszd.ktmc,
		"xszd.skpthyh":xszd.skpthyh,
		"xszd.jtskwz":xszd.jtskwz,
		"xszd.qqqh":xszd.qqqh,
		"xszd.jgh":xszd.jgh,
		"xszd.kclbmc":xszd.kclbmc,
		"xszd.zzmm":xszd.zzmm
	};
	$("#bot_ul").children().each(function(index, item){
		var datas = $(this).attr("data");
		var data = datas.split("~");
		if(index == 0){
			requestMap["xnm"] = data[2];
			requestMap["xqm"] = data[3];
			requestMap["xqh_id"] = data[10];
		}		
		requestMap["modelList["+index+"].tjkbzdm"] = data[0];
		requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
		requestMap["modelList["+index+"].xnm"] = data[2];
		requestMap["modelList["+index+"].xqm"] = data[3];
		requestMap["modelList["+index+"].xnmc"] = data[4];
		requestMap["modelList["+index+"].xqmmc"] = data[5];
		requestMap["modelList["+index+"].njdm_id"] =data[6];
		requestMap["modelList["+index+"].zyh_id"] = data[7];
		requestMap["modelList["+index+"].bh_id"] = data[8];
		requestMap["modelList["+index+"].tjkbmc"] = data[9];
		requestMap["modelList["+index+"].xqh_id"] = data[10];
		requestMap["modelList["+index+"].zymc"] = data[11];
		requestMap["modelList["+index+"].jgmc"] = data[12];
		requestMap["modelList["+index+"].bjmc"] = data[13];
		requestMap["modelList["+index+"].xkrs"] = data[14];
		requestMap["modelList["+index+"].jsxm"] = data[15];
		requestMap["modelList["+index+"].lxdh"] = data[16];
		requestMap["modelList["+index+"].bh"] = data[18];
		requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList["+index+"].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList["+index+"].zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
		requestMap["modelList["+index+"].akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
		requestMap["modelList["+index+"].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
	});
	$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcZjjczykbpdf.html",requestMap).submit();
});

//批量输出Excel
function batchShcExcel(type){
	//var selrows = $("#tabGrid").getGridParam("selarrrow");
	if($("#bot_ul").children().length == 0){
		$.alert($.i18n.get("alert6"));
		return false;
	}
	var requestMap = {
		"xszd.sj" 	: xszd.sj,
		"xszd.cd" 	: xszd.cd,
		"xszd.js" 	: xszd.js,
		"xszd.jszc" : xszd.jszc,
		"xszd.jxb" 	: xszd.jxb,
		"xszd.jxbzc" 	: xszd.jxbzc,
		"xszd.kcxzjc" 	: xszd.kcxzjc,
		"xszd.jxbrs" 	: xszd.jxbrs,
		"xszd.zyfx" : xszd.zyfx,
		"xszd.xkrs" 	: xszd.xkrs,
		"xszd.khfs" : xszd.khfs,
		"xszd.ksfs" : xszd.ksfs,
		"xszd.xkbz" : xszd.xkbz,
		"xszd.kcxszc" : xszd.kcxszc,
		"xszd.zhxs" : xszd.zhxs,
		"xszd.zxs" : xszd.zxs,
		"xszd.xf"  : xszd.xf,
		"xszd.zzrl"  : xszd.zzrl,
		"xszd.xq"  : xszd.xq,
		"xszd.skfsmc"  : xszd.skfsmc,
		"xszd.zxxx"  : xszd.zxxx,
		"xszd.zfj"  : xszd.zfj,
		"xszd.kch"  : xszd.kch,
		"xszd.kczxs":xszd.kczxs,
		"xszd.zyhxkcbj":xszd.zyhxkcbj,
		"xszd.cdlbmc":xszd.cdlbmc,
		"xszd.ktmc":xszd.ktmc,
		"xszd.skpthyh":xszd.skpthyh,
		"xszd.jtskwz":xszd.jtskwz,
		"xszd.qqqh":xszd.qqqh,
		"xszd.jgh":xszd.jgh,
		"xszd.kclbmc":xszd.kclbmc,
		"xszd.zzmm":xszd.zzmm
	};
	
	$("#bot_ul").children().each(function(index, item){
		var datas = $(this).attr("data");
		var data = datas.split("~");
		
		if(index == 0){
			requestMap["xnm"] = data[2];
			requestMap["xqm"] = data[3];
			requestMap["xqh_id"] = data[10];
		}		
		requestMap["modelList["+index+"].tjkbzdm"] = data[0];
		requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
		requestMap["modelList["+index+"].xnm"] = data[2];
		requestMap["modelList["+index+"].xqm"] = data[3];
		requestMap["modelList["+index+"].xnmc"] = data[4];
		requestMap["modelList["+index+"].xqmmc"] = data[5];
		requestMap["modelList["+index+"].njdm_id"] =data[6];
		requestMap["modelList["+index+"].zyh_id"] = data[7];
		requestMap["modelList["+index+"].bh_id"] = data[8];
		requestMap["modelList["+index+"].tjkbmc"] = data[9];
		requestMap["modelList["+index+"].xqh_id"] = data[10];
		requestMap["modelList["+index+"].zymc"] = data[11];
		requestMap["modelList["+index+"].jgmc"] = data[12];
		requestMap["modelList["+index+"].bjmc"] = data[13];
		requestMap["modelList["+index+"].xkrs"] = data[14];
		requestMap["modelList["+index+"].jsxm"] = data[15];
		requestMap["modelList["+index+"].lxdh"] = data[16];
		requestMap["modelList["+index+"].bh"] = data[18];
		requestMap["modelList["+index+"].njmc"] = data[19];
		requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList["+index+"].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList["+index+"].zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
		requestMap["modelList["+index+"].akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
		requestMap["modelList["+index+"].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
	});
	var map = {};
	map["kzlx"] ="dy";
	map["xnm"] =requestMap["modelList[0].xnm"];
	map["xqm"] =requestMap["modelList[0].xqm"];
	$.ajax({
		   type: "POST",
		   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
		   async: false,
		   data: map,
		   datatype: "json",
		   success: function(data){
			   if(data.indexOf("true") > -1){
				   $.alert($.i18n.get("alert4"));
					flag=true;
			   }else{
					if(type=='one'){
						if($("#xxdm").val()=="13422" ||$("#xxdm").val()=="10667"){
							$.post(_path+"/kbdy/bjkbdy_cxDcExcelBjkb.html?doType=one",requestMap, function (data) {	
                    			if(data.indexOf("success")!=-1){
                    				window.open(data.split("#success")[0]);
                    			}else{
                    				$.alert(data);
                    			}
                            }, 'json');
                    		
						}else{
							$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb.html?doType=one",requestMap).submit();
						}
						
					}else if(type=='manySheet'){
						if($("#bot_ul").children().length>255){
							$.alert("选择记录不能超过255条，目前Excel工作表sheet支持个数最多为255！");
							return false;
						}
						$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb.html?doType=manySheet",requestMap).submit();
					}else{
						if($("#xxdm").val()=='13754'){
                    		$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb13754.html",requestMap).submit();
                    	}else{
							$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkb.html",requestMap).submit();
						}
						
					}
			   }
		   }
		});
}

//批量输出Excel(纵向)
$("#plscexcel_btn").on("click",function(){
	if($("#bot_ul").children().length == 0){
		$.alert($.i18n.get("alert6"));
		return false;
	}
	var requestMap = {
		"xszd.sj" 	: xszd.sj,
		"xszd.cd" 	: xszd.cd,
		"xszd.js" 	: xszd.js,
		"xszd.jszc" : xszd.jszc,
		"xszd.jxb" 	: xszd.jxb,
		"xszd.jxbzc" 	: xszd.jxbzc,
		"xszd.kcxzjc" 	: xszd.kcxzjc,
		"xszd.jxbrs" 	: xszd.jxbrs,
		"xszd.zyfx" : xszd.zyfx,
		"xszd.xkrs" 	: xszd.xkrs,
		"xszd.khfs" : xszd.khfs,
		"xszd.ksfs" : xszd.ksfs,
		"xszd.xkbz" : xszd.xkbz,
		"xszd.kcxszc" : xszd.kcxszc,
		"xszd.zhxs" : xszd.zhxs,
		"xszd.zxs" : xszd.zxs,
		"xszd.xf"  : xszd.xf,
		"xszd.zzrl"  : xszd.zzrl,
		"xszd.xq"  : xszd.xq,
		"xszd.skfsmc"  : xszd.skfsmc,
		"xszd.zxxx"  : xszd.zxxx,
		"xszd.zfj"  : xszd.zfj,
		"xszd.kch"  : xszd.kch,
		"xszd.kczxs":xszd.kczxs,
		"xszd.zyhxkcbj":xszd.zyhxkcbj,
		"xszd.cdlbmc":xszd.cdlbmc,
		"xszd.ktmc":xszd.ktmc,
		"xszd.skpthyh":xszd.skpthyh,
		"xszd.jtskwz":xszd.jtskwz,
		"xszd.qqqh":xszd.qqqh,
		"xszd.jgh":xszd.jgh,
		"xszd.kclbmc":xszd.kclbmc,
		"xszd.zzmm":xszd.zzmm
	};
	
	$("#bot_ul").children().each(function(index, item){
		var datas = $(this).attr("data");
		var data = datas.split("~");
		
		if(index == 0){
			requestMap["xnm"] = data[2];
			requestMap["xqm"] = data[3];
			requestMap["xqh_id"] = data[10];
		}		
		requestMap["modelList["+index+"].tjkbzdm"] = data[0];
		requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
		requestMap["modelList["+index+"].xnm"] = data[2];
		requestMap["modelList["+index+"].xqm"] = data[3];
		requestMap["modelList["+index+"].xnmc"] = data[4];
		requestMap["modelList["+index+"].xqmmc"] = data[5];
		requestMap["modelList["+index+"].njdm_id"] =data[6];
		requestMap["modelList["+index+"].zyh_id"] = data[7];
		requestMap["modelList["+index+"].bh_id"] = data[8];
		requestMap["modelList["+index+"].tjkbmc"] = data[9];
		requestMap["modelList["+index+"].xqh_id"] = data[10];
		requestMap["modelList["+index+"].zymc"] = data[11];
		requestMap["modelList["+index+"].jgmc"] = data[12];
		requestMap["modelList["+index+"].bjmc"] = data[13];
		requestMap["modelList["+index+"].xkrs"] = data[14];
		requestMap["modelList["+index+"].jsxm"] = data[15];
		requestMap["modelList["+index+"].lxdh"] = data[16];
		requestMap["modelList["+index+"].bh"] = data[18];
		requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList["+index+"].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList["+index+"].zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
		requestMap["modelList["+index+"].akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
		requestMap["modelList["+index+"].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
	});
	var map = {};
	map["kzlx"] ="dy";
	map["xnm"] =requestMap["modelList[0].xnm"];
	map["xqm"] =requestMap["modelList[0].xqm"];
	$.ajax({
		   type: "POST",
		   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
		   async: false,
		   data: map,
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

//批量输出Excel(列表)
$("#plsclb_btn").on("click",function(){
	//var selrows = $("#tabGrid").getGridParam("selarrrow");
	if($("#bot_ul").children().length == 0){
		$.alert($.i18n.get("alert6"));
		return false;
	}
	var requestMap = {
		"xszd.sj" 	: xszd.sj,
		"xszd.cd" 	: xszd.cd,
		"xszd.js" 	: xszd.js,
		"xszd.jszc" : xszd.jszc,
		"xszd.jxb" 	: xszd.jxb,
		"xszd.jxbzc" 	: xszd.jxbzc,
		"xszd.kcxzjc" 	: xszd.kcxzjc,
		"xszd.jxbrs" 	: xszd.jxbrs,
		"xszd.zyfx" : xszd.zyfx,
		"xszd.xkrs" 	: xszd.xkrs,
		"xszd.khfs" : xszd.khfs,
		"xszd.ksfs" : xszd.ksfs,
		"xszd.xkbz" : xszd.xkbz,
		"xszd.kcxszc" : xszd.kcxszc,
		"xszd.zhxs" : xszd.zhxs,
		"xszd.zxs" : xszd.zxs,
		"xszd.xf"  : xszd.xf,
		"xszd.zzrl"  : xszd.zzrl,
		"xszd.xq"  : xszd.xq,
		"xszd.skfsmc"  : xszd.skfsmc,
		"xszd.zxxx"  : xszd.zxxx,
		"xszd.kch"  : xszd.kch,
		"xszd.kczxs":xszd.kczxs,
		"xszd.zyhxkcbj":xszd.zyhxkcbj,
		"xszd.cdlbmc":xszd.cdlbmc,
		"xszd.ktmc":xszd.ktmc,
		"xszd.skpthyh":xszd.skpthyh,
		"xszd.jtskwz":xszd.jtskwz,
		"xszd.qqqh":xszd.qqqh,
		"xszd.jgh":xszd.jgh,
		"xszd.kclbmc":xszd.kclbmc,
		"xszd.zzmm":xszd.zzmm
	};
	
	$("#bot_ul").children().each(function(index, item){
		var datas = $(this).attr("data");
		var data = datas.split("~");
		
		if(index == 0){
			requestMap["xnm"] = data[2];
			requestMap["xqm"] = data[3];
			requestMap["xqh_id"] = data[10];
		}		
		requestMap["modelList["+index+"].tjkbzdm"] = data[0];
		requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
		requestMap["modelList["+index+"].xnm"] = data[2];
		requestMap["modelList["+index+"].xqm"] = data[3];
		requestMap["modelList["+index+"].xnmc"] = data[4];
		requestMap["modelList["+index+"].xqmmc"] = data[5];
		requestMap["modelList["+index+"].njdm_id"] =data[6];
		requestMap["modelList["+index+"].zyh_id"] = data[7];
		requestMap["modelList["+index+"].bh_id"] = data[8];
		requestMap["modelList["+index+"].tjkbmc"] = data[9];
		requestMap["modelList["+index+"].xqh_id"] = data[10];
		requestMap["modelList["+index+"].zymc"] = data[11];
		requestMap["modelList["+index+"].jgmc"] = data[12];
		requestMap["modelList["+index+"].bjmc"] = data[13];
		requestMap["modelList["+index+"].xkrs"] = data[14];
		requestMap["modelList["+index+"].jsxm"] = data[15];
		requestMap["modelList["+index+"].lxdh"] = data[16];
		requestMap["modelList["+index+"].bh"] = data[18];
		requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList["+index+"].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList["+index+"].zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
		requestMap["modelList["+index+"].akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
		requestMap["modelList["+index+"].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
	});
	//构建form
	$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelBjkblb.html",requestMap).submit();
});
//全校课表
function qxkb(type){
	//var selrows = $("#tabGrid").getGridParam("selarrrow");
	if($("#bot_ul").children().length == 0){
		$.alert($.i18n.get("alert6"));
		return false;
	}
	
	
	var requestMap = {
		"kblx"      : 'bjkb',	
		"doType" 	: type,
		"xszd.sj" 	: xszd.sj,
		"xszd.cd" 	: xszd.cd,
		"xszd.js" 	: xszd.js,
		"xszd.jszc" : xszd.jszc,
		"xszd.jxbzc" 	: xszd.jxbzc,
		"xszd.jxb" 	: xszd.jxb,
		"xszd.kcxzjc" 	: xszd.kcxzjc,
		"xszd.jxbrs" 	: xszd.jxbrs,
		"xszd.zyfx" : xszd.zyfx,
		"xszd.xkrs" 	: xszd.xkrs,
		"xszd.khfs" : xszd.khfs,
		"xszd.ksfs" : xszd.ksfs,
		"xszd.xkbz" : xszd.xkbz,
		"xszd.kcxszc" : xszd.kcxszc,
		"xszd.zhxs" : xszd.zhxs,
		"xszd.zxs" : xszd.zxs,
		"xszd.xf"  : xszd.xf,
		"xszd.zzrl"  : xszd.zzrl,
		"xszd.bxsjc"  : xszd.bxsjc,
		"xszd.bxszm"  : xszd.bxszm,
		"xszd.xq"  : xszd.xq,
		"xszd.skfsmc"  : xszd.skfsmc,
		"xszd.zxxx"  : xszd.zxxx,
		"xszd.zfj"  : xszd.zfj,
		"xszd.kch"  : xszd.kch,
		"xszd.kczxs":xszd.kczxs,
		"xszd.zyhxkcbj":xszd.zyhxkcbj,
		"xszd.cdlbmc":xszd.cdlbmc,
		"xszd.ktmc":xszd.ktmc,
		"xszd.skpthyh":xszd.skpthyh,
		"xszd.jtskwz":xszd.jtskwz,
		"xszd.qqqh":xszd.qqqh,
		"xszd.jgh":xszd.jgh,
		"xszd.kclbmc":xszd.kclbmc,
		"xszd.zzmm":xszd.zzmm
//		"bxsjc":$("#bxsjc:checked").val(),
//		"bxszm":$("#bxszm:checked").val()
	};
	
	$("#bot_ul").children().each(function(index, item){
		var datas = $(this).attr("data");
		var data = datas.split("~");
		
		if(index == 0){
			requestMap["xnm"] = data[2];
			requestMap["xqm"] = data[3];
			requestMap["xnmc"] = data[4];
			requestMap["xqmmc"] = data[5];			
			requestMap["xqh_id"] = data[10];
		}		
		requestMap["modelList["+index+"].tjkbzdm"] = data[0];
		requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
		requestMap["modelList["+index+"].xnm"] = data[2];
		requestMap["modelList["+index+"].xqm"] = data[3];
		requestMap["modelList["+index+"].xnmc"] = data[4];
		requestMap["modelList["+index+"].xqmmc"] = data[5];
		requestMap["modelList["+index+"].njdm_id"] =data[6];
		requestMap["modelList["+index+"].zyh_id"] = data[7];
		requestMap["modelList["+index+"].bh_id"] = data[8];
		requestMap["modelList["+index+"].tjkbmc"] = data[9];
		requestMap["modelList["+index+"].xqh_id"] = data[10];
		requestMap["modelList["+index+"].jgmc"] = data[12];
		requestMap["modelList["+index+"].xkrs"] = data[14];
		requestMap["modelList["+index+"].jsxm"] = data[15];
		requestMap["modelList["+index+"].bh"] = data[18];
		requestMap["modelList["+index+"].zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
		requestMap["modelList["+index+"].akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
		requestMap["modelList["+index+"].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["modelList["+index+"].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["modelList["+index+"].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
		requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
		requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
		requestMap["modelList["+index+"].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
	});	
	
	var map = {};
	map["kzlx"] ="dy";
	map["xnm"] =requestMap["modelList[0].xnm"];
	map["xqm"] =requestMap["modelList[0].xqm"];
	$.ajax({
		   type: "POST",
		   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
		   async: false,
		   data: map,
		   datatype: "json",
		   success: function(data){
			   if(data.indexOf("true") > -1){
				   $.alert($.i18n.get("alert4"));
					flag=true;
			   }else{
				   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcQxkb.html",requestMap).submit();
			   }
		   }
		});
	//构建form
//	$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcQxkb.html",requestMap).submit();
}


//全校课表
function scdkb(type){
	//var selrows = $("#tabGrid").getGridParam("selarrrow");
	if($("#bot_ul").children().length == 0){
		$.alert($.i18n.get("alert6"));
		return false;
	}
	
	
	var requestMap = {
		"kblx"      : 'bjkb',	
		"doType" 	: type,
		"xszd.sj" 	: xszd.sj,
		"xszd.cd" 	: xszd.cd,
		"xszd.js" 	: xszd.js,
		"xszd.jszc" : xszd.jszc,
		"xszd.jxbzc" 	: xszd.jxbzc,
		"xszd.jxb" 	: xszd.jxb,
		"xszd.kcxzjc" 	: xszd.kcxzjc,
		"xszd.jxbrs" 	: xszd.jxbrs,
		"xszd.zyfx" : xszd.zyfx,
		"xszd.xkrs" 	: xszd.xkrs,
		"xszd.khfs" : xszd.khfs,
		"xszd.ksfs" : xszd.ksfs,
		"xszd.xkbz" : xszd.xkbz,
		"xszd.kcxszc" : xszd.kcxszc,
		"xszd.zhxs" : xszd.zhxs,
		"xszd.zxs" : xszd.zxs,
		"xszd.xf"  : xszd.xf,
		"xszd.zzrl"  : xszd.zzrl,
		"xszd.bxsjc"  : xszd.bxsjc,
		"xszd.bxszm"  : xszd.bxszm,
		"xszd.xq"  : xszd.xq,
		"xszd.skfsmc"  : xszd.skfsmc,
		"xszd.zxxx"  : xszd.zxxx,
		"xszd.kch"  : xszd.kch,
		"xszd.kczxs":xszd.kczxs,
		"xszd.zyhxkcbj":xszd.zyhxkcbj,
		"xszd.cdlbmc":xszd.cdlbmc,
		"xszd.ktmc":xszd.ktmc,
		"xszd.skpthyh":xszd.skpthyh,
		"xszd.jtskwz":xszd.jtskwz,
		"xszd.qqqh":xszd.qqqh,
		"xszd.jgh":xszd.jgh,
		"xszd.kclbmc":xszd.kclbmc,
		"xszd.zzmm":xszd.zzmm
	};
	
	$("#bot_ul").children().each(function(index, item){
		var datas = $(this).attr("data");
		var data = datas.split("~");
		
		if(index == 0){
			requestMap["xnm"] = data[2];
			requestMap["xqm"] = data[3];
			requestMap["xnmc"] = data[4];
			requestMap["xqmmc"] = data[5];			
			requestMap["xqh_id"] = data[10];
		}		
		requestMap["modelList["+index+"].tjkbzdm"] = data[0];
		requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
		requestMap["modelList["+index+"].xnm"] = data[2];
		requestMap["modelList["+index+"].xqm"] = data[3];
		requestMap["modelList["+index+"].xnmc"] = data[4];
		requestMap["modelList["+index+"].xqmmc"] = data[5];
		requestMap["modelList["+index+"].njdm_id"] =data[6];
		requestMap["modelList["+index+"].zyh_id"] = data[7];
		requestMap["modelList["+index+"].bh_id"] = data[8];
		requestMap["modelList["+index+"].tjkbmc"] = data[9];
		requestMap["modelList["+index+"].xqh_id"] = data[10];
		requestMap["modelList["+index+"].zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
		requestMap["modelList["+index+"].akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
	});	
	//构建form
	$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxScdkb.html",requestMap).submit();
}



/**
 * 杨凌职业技术学院
 */
function batchShcQxkbExcel(){
//$("#qxkbplscexcel_btn").on("click",function(){
	var map = {};
	map["kzlx"] ="dy";
	var selrows = $("#tabGrid").getGridParam("selarrrow");
	var requestMap = {};
	if(selrows.length == 0){
		var map = {};
		map["xnm"] = $('#xnm').val();
		map["xqm"] = $('#xqm').val();
		requestMap["xnm"] = $('#xnm').val();
		requestMap["xqm"] = $('#xqm').val();
		requestMap["xnmc"] = $("#xnm").find("option:selected").text();
		requestMap["xqmmc"] = $("#xqm").find("option:selected").text();
		requestMap["xqh_id"] = $('#xqh_id').val();
		requestMap["njdm_id"] = $('#njdm_id').val();
		requestMap["jg_id"] = $('#jg_id').val();
		requestMap["zyh_id"] = $('#zyh_id').val();
		requestMap["zyfx_id"] = $("#zyfx_id").val()||'';
		requestMap["bh_id"] = $('#bh_id').val()||'';
		requestMap["xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
		requestMap["pyccdm"] = $("#pyccdm").val()||'';
		requestMap["kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
		requestMap["sfzhsjk"] = $("#sfzhsjk").val()||'';
		requestMap["kbsjlyqz"] = $("#kbsjlyqz").val()||'';
		requestMap["zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
		requestMap["akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
		requestMap["zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		requestMap["yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val()
	}else{
		$("#bot_ul").children().each(function(index, item){
			var datas = $(this).attr("data");
			var data = datas.split("~");
			if(index == 0){
				requestMap["xnm"] = data[2];
				requestMap["xqm"] = data[3];
				requestMap["xnmc"] = data[4];
				requestMap["xqmmc"] = data[5];
				requestMap["xqh_id"] = data[10];
			}		
			requestMap["modelList["+index+"].tjkbzdm"] = data[0];
			requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
			requestMap["modelList["+index+"].xnm"] = data[2];
			requestMap["modelList["+index+"].xqm"] = data[3];
			requestMap["modelList["+index+"].xnmc"] = data[4];
			requestMap["modelList["+index+"].xqmmc"] = data[5];
			requestMap["modelList["+index+"].njdm_id"] =data[6];
			requestMap["modelList["+index+"].zyh_id"] = data[7];
			requestMap["modelList["+index+"].bh_id"] = data[8];
			requestMap["modelList["+index+"].tjkbmc"] = data[9];
			requestMap["modelList["+index+"].xqh_id"] = data[10];
			requestMap["modelList["+index+"].zymc"] = data[11];
			requestMap["modelList["+index+"].jgmc"] = data[12];
			requestMap["modelList["+index+"].bjmc"] = data[13];
			requestMap["modelList["+index+"].xkrs"] = data[14];
			requestMap["modelList["+index+"].jsxm"] = data[15];
			requestMap["modelList["+index+"].lxdh"] = data[16];
			requestMap["modelList["+index+"].bh"] = data[18];
			requestMap["modelList["+index+"].njmc"] = data[19];
			requestMap["modelList["+index+"].jgdm"] = data[20];
			requestMap["modelList["+index+"].zyh"] = data[21];
			requestMap["modelList["+index+"].njdm"] = data[22];
			requestMap["modelList["+index+"].xsxyxh"] = data[23];
			requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
			requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
			requestMap["modelList["+index+"].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
		});
		map["xnm"] =requestMap["modelList[0].xnm"];
		map["xqm"] =requestMap["modelList[0].xqm"];
	}
	$.ajax({
		   type: "POST",
		   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
		   async: false,
		   data: map,
		   datatype: "json",
		   success: function(data){
			   if(data.indexOf("true") > -1){
				   $.alert($.i18n.get("alert4"));
					flag=true;
			   }else{
				   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelQxkb10966.html",requestMap).submit(); 
			   }
		   }
		});
//});
}

jQuery(function($){
	$("#tabGrid").loadJqGrid(grid);
	
	var param = {};
	param.xb_id = $.founded($("#xb_id").val())?$("#xb_id").val():"";
	param.jg_id = $("#jg_id").val();
	param.zyh_id = $("#zyh_id").val();
	if($("#xxdm").val()!='10335'){
		param.bh_id = $("#bh_id").val()||'';
	}
	param.njdm_id = $("#njdm_id").val();
	if($("#xxdm").val()=='10335'){
		param.rangeable = "false";
		param.doWhat="tjkb";
		param.xnm=$('#xnm').val();
		param.xqm=$('#xqm').val()>15?'240':'15';
		param.xxdm="10335";
		// $.bindChangeEvent("#jg_id","","#zyh_id","#zyfx_id","","#njdm_id", param, function (){},$("#localeKey").val()=="en_US"?"All":"全部",true);//中英文级联 全部
		$.xbChangeEvent("#xb_id","#jg_id","","#zyh_id","#zyfx_id","","#njdm_id", param, function (){},$("#localeKey").val()=="en_US"?"All":"全部",{showSearch:true, ctnAll:true, ctnInvert:true},{dataType: '11'});
	}else if($("#xxdm").val()=='13290' || ($("#xxdm").val()=='10619' && $("#jsdm").val()=='js')){
		param.rangeable = "false";
		// $.bindChangeEvent("#jg_id","","#zyh_id","#zyfx_id","#bh_id","#njdm_id", param, function (){},$("#localeKey").val()=="en_US"?"All":"全部",true);//中英文级联 全部
		$.xbChangeEvent("#xb_id","#jg_id","","#zyh_id","#zyfx_id","#bh_id","#njdm_id", param, function (){},$("#localeKey").val()=="en_US"?"All":"全部",{showSearch:true, ctnAll:true, ctnInvert:true},{dataType: $("#xxdm").val()=='13290'?'11':'10'});
	}else{
		// $.bindChangeEvent("#jg_id","","#zyh_id","#zyfx_id","#bh_id","#njdm_id", param, function (){},$("#localeKey").val()=="en_US"?"All":"全部",true);//中英文级联 全部
		$.xbChangeEvent("#xb_id","#jg_id","","#zyh_id","#zyfx_id","#bh_id","#njdm_id", param, function (){},$("#localeKey").val()=="en_US"?"All":"全部",{showSearch:true, ctnAll:true, ctnInvert:true},{dataType: '10'});
	}
	
	//获取配置的显示字段信息
	$.post(_path + '/kbdy/bjkbdy_cxKbzdxsxx.html', {"kbzl":"bj","doType":"query"} , function(rst){
		$.each(rst,function(index,item){
			xszd[item.ZDM] = item.SFXS == "1";
		});
	});
	
	//显示字段配置页面
	$("#xszdConfig").click(function(){
		$.showDialog(_path +'/kbdy/bjkbdy_cxKbzdxsxx.html?kbzl=bj','设置',$.extend(true,{},viewConfig,{
			width:"500px",
			modalName:"xszdConfigModal"
		}));
		
	});
	
	$(document).off('change', '#xnm,#xqm').on('change', '#xnm,#xqm', function (e) {
		jQuery.ajaxSetup({async:false});
	    var html = [];
		$.getJSON(_path + "/kbdy/bjkbdy_cxZcxx.html",{"xnm":$("#xnm").val(),"xqm":$("#xqm").val()}, function(data){
			html.push('<option value="">全部</option>');
			if($.founded(data)){
				$.each(data||[],function(i,rowObj){
					html.push('<option value="'+rowObj["zs"]+'">'+rowObj["zsmc"]+'</option>');
				});
			}
		});
		$("#zs").empty().append(html.join(""));
		jQuery.ajaxSetup({async:true});
		$("#zs").trigger("chosen:updated");
	})
	
	$("#btn_gd").click(function(){
		if($("#ajaxForm").validateForm("isValid")){
			var requestMap ={};
			var map ={};
			requestMap["xnm"]= $("#xnm").val();
			requestMap["xqm"]= $("#xqm").val();
			requestMap["dyly"] = "gd13289"; 
			jQuery.ajaxSetup({async:false});
			jQuery.post(_path+'/kbdy/bjkbdy_cxBjkbdyTjkbList.html',requestMap,function(data){
				var items = data.items;
				if(items.length==0){
					$.error("归档的班级课程表为空，无法归档!");
				}else{
				    map["xszd.kch"]=xszd.kch;
				    map["xszd.sj"]=xszd.sj;
				    map["xszd.cd"]=xszd.cd;
				    map["xszd.js"]=xszd.js;
				    map["xszd.jszc"]=xszd.jszc;
				    map["xszd.jxb"]=xszd.jxb;
				    map["xszd.jxbzc"]=xszd.jxbzc;
				    map["xszd.zyfx"]=xszd.zyfx;
				    map["xszd.jxbrs"]=xszd.jxbrs;
				    map["xszd.xkrs"]=xszd.xkrs;
				    map["xszd.kcxzjc"]=xszd.kcxzjc;
				    map["xszd.khfs"]=xszd.khfs;
				    map["xszd.ksfs"]=xszd.ksfs;
				    map["xszd.xkbz"]=xszd.xkbz;//选课备注
				    map["xszd.kcxszc"]=xszd.kcxszc;//课程学时组成
				    map["xszd.zhxs"]=xszd.zhxs;//周学时
				    map["xszd.zxs"]=xszd.zxs;
				    map["xszd.xf"]=xszd.xf;
				    map["xszd.zzrl"]=xszd.zzrl;
				    map["xszd.xq"]=xszd.xq;
				    map["xszd.zxxx"]=xszd.zxxx;
				    map["xszd.skfsmc"]=xszd.skfsmc;
				    map["xszd.zfj"]=xszd.zfj;
				    map["xszd.kczxs"]=xszd.kczxs;
				    
				    var item=items[0];
				    map["xnm"]= item.xnm;
				    map["xqm"]= item.xqm;
				    map["xqh_id"]= item.xqh_id;
				    map["xnmc"]= $("#xnm").find("option[value='"+$("#xnm").val()+"']").text();
				    map["xqmmc"]= $("#xqm").find("option[value='"+$("#xqm").val()+"']").text();
				    for(var k=0;k<items.length;k++){
				    	map["modelList["+k+"].tjkbzdm"]= items[k].tjkbzdm || '';
				    	map["modelList["+k+"].tjkbzxsdm"]= items[k].tjkbzxsdm || '';
					    map["modelList["+k+"].xnm"]= items[k].xnm || '';
					    map["modelList["+k+"].xqm"]= items[k].xqm || '';
					    map["modelList["+k+"].xnmc"]= items[k].xnmc || '';
					    map["modelList["+k+"].xqmmc"]= items[k].xqmmc || '';
					    map["modelList["+k+"].njdm_id"]= items[k].njdm_id || '';
					    map["modelList["+k+"].zyh_id"]= items[k].zyh_id || '';
					    map["modelList["+k+"].bh_id"]= items[k].bh_id || '';
					    map["modelList["+k+"].tjkbmc"]=items[k].tjkbmc || '';
					    map["modelList["+k+"].xqh_id"]=items[k].xqh_id || '';
					    map["modelList["+k+"].zymc"]=items[k].zymc || '';
					    map["modelList["+k+"].jgmc"]=items[k].jgmc || '';
					    map["modelList["+k+"].bjmc"]= items[k].bj || '';
					    map["modelList["+k+"].xkrs"]=items[k].xkrs || '';
					    map["modelList["+k+"].jsxm"]= items[k].jsxm || '';
					    map["modelList["+k+"].lxdh"]=items[k].lxdh || '';
					    map["modelList["+k+"].zxszjjs"] = items[k].zxszjjs || '';
					    map["modelList["+k+"].bj"]= items[k].bj || '';
					    map["modelList["+k+"].bh"]=items[k].bh || '';
					    map["modelList["+k+"].zs"] = "";
					    map["modelList["+k+"].xsdm"] = "";
				    }
				    map["kzlx"] = "dy";
				    map["dyly"] = "gd13289";
				    jQuery.post(_path+'/kbdy/bjkbdy_cxDcExcelBjkb.html',map,function(data){
				    	if(data.indexOf("成功") > -1){
				    		if(data.indexOf("成功") > -1){
								refershGrid("tabGrid");
								$.success(data);
								//关闭窗口
						    	$.closeModal("gdModal");
							}else{
								$.error(data);
							}
				    	}
				    });
				}
			},'json');
			jQuery.ajaxSetup({async:true});
		}
		
    	return false;//为false 不关闭窗口
	});
	$("#bjkbdy_btn").on("click",function(){
		var selrows = $("#tabGrid").getGridParam("selarrrow");
		if($("#bot_ul").children().length == 0){
		//if(selrows.length == 0){
			$.alert($.i18n.get("alert6"));
			return false;
		}
		var requestMap = {
			"kblx"      : 'bjkb',
			"doType" 	: 'kdkb',
			"xszd.sj" 	: xszd.sj,
			"xszd.cd" 	: xszd.cd,
			"xszd.js" 	: xszd.js,
			"xszd.jszc" : xszd.jszc,
			"xszd.jxb" 	: xszd.jxb,
			"xszd.zyfx" : xszd.zyfx,
			"xszd.xq"  : xszd.xq,
			"xszd.zxxx"  : xszd.zxxx,
			"xszd.kch"  : xszd.kch,
			"xszd.kczxs":xszd.kczxs,
			"xszd.zyhxkcbj":xszd.zyhxkcbj,
			"xszd.cdlbmc":xszd.cdlbmc,
			"xszd.ktmc":xszd.ktmc,
			"xszd.skpthyh":xszd.skpthyh,
			"xszd.jtskwz":xszd.jtskwz,
			"xszd.qqqh":xszd.qqqh,
			"xszd.jgh":xszd.jgh,
			"xszd.kclbmc":xszd.kclbmc,
			"xszd.zzmm":xszd.zzmm
			
		};

		$("#bot_ul").children().each(function(index, item){
			var datas = $(this).attr("data");
			var data = datas.split("~");
			if(index == 0){
				requestMap["xnm"] = data[2];
				requestMap["xqm"] = data[3];
				requestMap["xnmc"] = data[4];
				requestMap["xqmmc"] = data[5];
				requestMap["xqh_id"] = data[10];
			}		
			requestMap["modelList["+index+"].tjkbzdm"] = data[0];
			requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
			requestMap["modelList["+index+"].xnm"] = data[2];
			requestMap["modelList["+index+"].xqm"] = data[3];
			requestMap["modelList["+index+"].xnmc"] = data[4];
			requestMap["modelList["+index+"].xqmmc"] = data[5];
			requestMap["modelList["+index+"].njdm_id"] =data[6];
			requestMap["modelList["+index+"].zyh_id"] = data[7];
			requestMap["modelList["+index+"].bh_id"] = data[8];
			requestMap["modelList["+index+"].tjkbmc"] = data[9];
			requestMap["modelList["+index+"].xqh_id"] = data[10];
			requestMap["modelList["+index+"].zymc"] = data[11];
			requestMap["modelList["+index+"].jgmc"] = data[12];
			requestMap["modelList["+index+"].bjmc"] = data[13];
			requestMap["modelList["+index+"].xkrs"] = data[14];
			requestMap["modelList["+index+"].jsxm"] = data[15];
			requestMap["modelList["+index+"].lxdh"] = data[16];
			requestMap["modelList["+index+"].bh"] = data[18];
			requestMap["modelList["+index+"].njmc"] = data[19];
		});
		var map = {};
		map["kzlx"] ="dy";
		map["xnm"] =requestMap["modelList[0].xnm"];
		map["xqm"] =requestMap["modelList[0].xqm"];
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: map,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
					   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxPldcBjkb.html",requestMap).submit();
				   }
			   }
			});
		//构建form
	//	$.buildForm("downForm",_path+"/kbdy/bjkbdy_cxPldcBjkb.html",requestMap).submit();
	});
	
	$("#bjkbsc_btn").on("click",function(){
		var selrows = $("#tabGrid").getGridParam("selarrrow");
		if($("#bot_ul").children().length == 0){
		//if(selrows.length == 0){
			$.alert($.i18n.get("alert6"));
			return false;
		}
		var requestMap = {
			"kblx"      : 'bjkb',
			"doType" 	: 'kdkb',
			"xszd.sj" 	: xszd.sj,
			"xszd.cd" 	: xszd.cd,
			"xszd.js" 	: xszd.js,
			"xszd.jszc" : xszd.jszc,
			"xszd.jxb" 	: xszd.jxb,
			"xszd.zyfx" : xszd.zyfx,
			"xszd.xq"  : xszd.xq,
			"xszd.zxxx"  : xszd.zxxx,
			"xszd.kch"  : xszd.kch,
			"xszd.jgh":xszd.jgh,
			"xszd.kclbmc":xszd.kclbmc,
			"xszd.kczxs":xszd.kczxs
		};

		$("#bot_ul").children().each(function(index, item){
			var datas = $(this).attr("data");
			var data = datas.split("~");
			if(index == 0){
				requestMap["xnm"] = data[2];
				requestMap["xqm"] = data[3];
				requestMap["xnmc"] = data[4];
				requestMap["xqmmc"] = data[5];
				requestMap["xqh_id"] = data[10];
			}		
			requestMap["modelList["+index+"].tjkbzdm"] = data[0];
			requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
			requestMap["modelList["+index+"].xnm"] = data[2];
			requestMap["modelList["+index+"].xqm"] = data[3];
			requestMap["modelList["+index+"].xnmc"] = data[4];
			requestMap["modelList["+index+"].xqmmc"] = data[5];
			requestMap["modelList["+index+"].njdm_id"] =data[6];
			requestMap["modelList["+index+"].zyh_id"] = data[7];
			requestMap["modelList["+index+"].bh_id"] = data[8];
			requestMap["modelList["+index+"].tjkbmc"] = data[9];
			requestMap["modelList["+index+"].xqh_id"] = data[10];
			requestMap["modelList["+index+"].zymc"] = data[11];
			requestMap["modelList["+index+"].jgmc"] = data[12];
			requestMap["modelList["+index+"].bjmc"] = data[13];
			requestMap["modelList["+index+"].xkrs"] = data[14];
			requestMap["modelList["+index+"].jsxm"] = data[15];
			requestMap["modelList["+index+"].lxdh"] = data[16];
			requestMap["modelList["+index+"].bh"] = data[18];
			requestMap["modelList["+index+"].njmc"] = data[19];
		});
		//构建form
		$.buildForm("downForm",_path+"/kbdy/bjkbdy2_cxDcQxBjkb.html",requestMap).submit();
	});
	
	/**
	 * 学期课表输出Excel  成都理工大学工程技术学院
	 */
	$("#xqkbscexcel_btn").on("click",function(){
		var selrows = $("#tabGrid").getGridParam("selarrrow");
		if($("#bot_ul").children().length == 0){
			$.alert($.i18n.get("alert6"));
			return false;
		}
		var requestMap = {
			"kblx"      : 'bj',
			"doType" 	: 'kdkb',
			"xszd.sj" 	: xszd.sj,
			"xszd.cd" 	: xszd.cd,
			"xszd.js" 	: xszd.js,
			"xszd.jszc" : xszd.jszc,
			"xszd.jxb" 	: xszd.jxb,
			"xszd.zyfx" : xszd.zyfx,
			"xszd.xq"  : xszd.xq,
			"xszd.zxxx"  : xszd.zxxx,
			"xszd.kch"  : xszd.kch,
			"xszd.kczxs":xszd.kczxs
			
		};
		
		$("#bot_ul").children().each(function(index, item){
			var datas = $(this).attr("data");
			var data = datas.split("~");
			if(index == 0){
				requestMap["xnm"] = data[2];
				requestMap["xqm"] = data[3];
				requestMap["xnmc"] = data[4];
				requestMap["xqmmc"] = data[5];
				requestMap["xqh_id"] = data[10];
			}		
			requestMap["modelList["+index+"].tjkbzdm"] = data[0];
			requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
			requestMap["modelList["+index+"].xnm"] = data[2];
			requestMap["modelList["+index+"].xqm"] = data[3];
			requestMap["modelList["+index+"].xnmc"] = data[4];
			requestMap["modelList["+index+"].xqmmc"] = data[5];
			requestMap["modelList["+index+"].njdm_id"] =data[6];
			requestMap["modelList["+index+"].zyh_id"] = data[7];
			requestMap["modelList["+index+"].bh_id"] = data[8];
			requestMap["modelList["+index+"].tjkbmc"] = data[9];
			requestMap["modelList["+index+"].xqh_id"] = data[10];
			requestMap["modelList["+index+"].zymc"] = data[11];
			requestMap["modelList["+index+"].jgmc"] = data[12];
			requestMap["modelList["+index+"].bjmc"] = data[13];
			requestMap["modelList["+index+"].xkrs"] = data[14];
			requestMap["modelList["+index+"].jsxm"] = data[15];
			requestMap["modelList["+index+"].lxdh"] = data[16];
			requestMap["modelList["+index+"].bh"] = data[18];
			requestMap["modelList["+index+"].njmc"] = data[19];
			requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		});
		
		var map = {};
		map["kzlx"] ="dy";
		map["xnm"] =requestMap["modelList[0].xnm"];
		map["xqm"] =requestMap["modelList[0].xqm"];
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: map,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
					   if($("#xxdm").val()=='10654'){
						   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelxqkb10654.html",requestMap).submit();

					   }else{
						   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelxqkb.html",requestMap).submit();
					   }
				   }
			   }
			});
	});
	
	
	/**
	 * 长春医学高等专科学校    班级周次课表输出
	 */
	$("#bjzckbscexcel_btn").on("click",function(){
		var selrows = $("#tabGrid").getGridParam("selarrrow");
		if($("#bot_ul").children().length == 0){
		//if(selrows.length == 0){
			$.alert($.i18n.get("alert6"));
			return false;
		}
		var requestMap = {
			"kblx"      : 'bj',
			"xszd.sj" 	: xszd.sj,
			"xszd.cd" 	: xszd.cd,
			"xszd.js" 	: xszd.js,
			"xszd.jszc" : xszd.jszc,
			"xszd.jxb" 	: xszd.jxb,
			"xszd.jxbzc" 	: xszd.jxbzc,
			"xszd.kcxzjc" 	: xszd.kcxzjc,
			"xszd.jxbrs" 	: xszd.jxbrs,
			"xszd.zyfx" : xszd.zyfx,
			"xszd.xkrs" : xszd.xkrs,
			"xszd.khfs" : xszd.khfs,
			"xszd.ksfs" : xszd.ksfs,
			"xszd.xkbz" : xszd.xkbz,
			"xszd.kcxszc" : xszd.kcxszc,
			"xszd.zhxs" : xszd.zhxs,
			"xszd.zxs" : xszd.zxs,
			"xszd.xf"  : xszd.xf,
			"xszd.zzrl"  : xszd.zzrl,
			"xszd.xq"  : xszd.xq,
			"xszd.skfsmc"  : xszd.skfsmc,
			"xszd.zxxx"  : xszd.zxxx,
			"xszd.zfj"  : xszd.zfj,
			"xszd.kch"  : xszd.kch,
			"xszd.jgh":xszd.jgh,
			"xszd.kclbmc":xszd.kclbmc,
			"xszd.kczxs":xszd.kczxs
			
		};

		$("#bot_ul").children().each(function(index, item){
			var datas = $(this).attr("data");
			var data = datas.split("~");
			if(index == 0){
				requestMap["xnm"] = data[2];
				requestMap["xqm"] = data[3];
				requestMap["xnmc"] = data[4];
				requestMap["xqmmc"] = data[5];
				requestMap["xqh_id"] = data[10];
			}		
			requestMap["modelList["+index+"].tjkbzdm"] = data[0];
			requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
			requestMap["modelList["+index+"].xnm"] = data[2];
			requestMap["modelList["+index+"].xqm"] = data[3];
			requestMap["modelList["+index+"].xnmc"] = data[4];
			requestMap["modelList["+index+"].xqmmc"] = data[5];
			requestMap["modelList["+index+"].njdm_id"] =data[6];
			requestMap["modelList["+index+"].zyh_id"] = data[7];
			requestMap["modelList["+index+"].bh_id"] = data[8];
			requestMap["modelList["+index+"].tjkbmc"] = data[9];
			requestMap["modelList["+index+"].xqh_id"] = data[10];
			requestMap["modelList["+index+"].zymc"] = data[11];
			requestMap["modelList["+index+"].jgmc"] = data[12];
			requestMap["modelList["+index+"].bjmc"] = data[13];
			requestMap["modelList["+index+"].xkrs"] = data[14];
			requestMap["modelList["+index+"].jsxm"] = data[15];
			requestMap["modelList["+index+"].lxdh"] = data[16];
			requestMap["modelList["+index+"].bh"] = data[18];
			requestMap["modelList["+index+"].njmc"] = data[19];
			requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		});
		var map = {};
		map["kzlx"] ="dy";
		map["xnm"] =requestMap["modelList[0].xnm"];
		map["xqm"] =requestMap["modelList[0].xqm"];
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: map,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
					   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelZckb.html",requestMap).submit(); 
				   }
			   }
			});
	});
	
	
	/**
	 * 私立华联学院    班级课表输出
	 */
	$("#slhlbjkbscexcel_btn").on("click",function(){
		var selrows = $("#tabGrid").getGridParam("selarrrow");
		if($("#bot_ul").children().length == 0){
		//if(selrows.length == 0){
			$.alert($.i18n.get("alert6"));
			return false;
		}
		var requestMap = {
			"kblx"      : 'bj',
			"xszd.sj" 	: xszd.sj,
			"xszd.cd" 	: xszd.cd,
			"xszd.js" 	: xszd.js,
			"xszd.jszc" : xszd.jszc,
			"xszd.jxb" 	: xszd.jxb,
			"xszd.jxbzc" 	: xszd.jxbzc,
			"xszd.kcxzjc" 	: xszd.kcxzjc,
			"xszd.jxbrs" 	: xszd.jxbrs,
			"xszd.zyfx" : xszd.zyfx,
			"xszd.xkrs" : xszd.xkrs,
			"xszd.khfs" : xszd.khfs,
			"xszd.ksfs" : xszd.ksfs,
			"xszd.xkbz" : xszd.xkbz,
			"xszd.kcxszc" : xszd.kcxszc,
			"xszd.zhxs" : xszd.zhxs,
			"xszd.zxs" : xszd.zxs,
			"xszd.xf"  : xszd.xf,
			"xszd.zzrl"  : xszd.zzrl,
			"xszd.xq"  : xszd.xq,
			"xszd.skfsmc"  : xszd.skfsmc,
			"xszd.zxxx"  : xszd.zxxx,
			"xszd.zfj"  : xszd.zfj,
			"xszd.kch"  : xszd.kch,
			"xszd.jgh":xszd.jgh,
			"xszd.kclbmc":xszd.kclbmc,
			"xszd.kczxs":xszd.kczxs
			
		};

		$("#bot_ul").children().each(function(index, item){
			var datas = $(this).attr("data");
			var data = datas.split("~");
			if(index == 0){
				requestMap["xnm"] = data[2];
				requestMap["xqm"] = data[3];
				requestMap["xnmc"] = data[4];
				requestMap["xqmmc"] = data[5];
				requestMap["xqh_id"] = data[10];
			}		
			requestMap["modelList["+index+"].tjkbzdm"] = data[0];
			requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
			requestMap["modelList["+index+"].xnm"] = data[2];
			requestMap["modelList["+index+"].xqm"] = data[3];
			requestMap["modelList["+index+"].xnmc"] = data[4];
			requestMap["modelList["+index+"].xqmmc"] = data[5];
			requestMap["modelList["+index+"].njdm_id"] =data[6];
			requestMap["modelList["+index+"].zyh_id"] = data[7];
			requestMap["modelList["+index+"].bh_id"] = data[8];
			requestMap["modelList["+index+"].tjkbmc"] = data[9];
			requestMap["modelList["+index+"].xqh_id"] = data[10];
			requestMap["modelList["+index+"].zymc"] = data[11];
			requestMap["modelList["+index+"].jgmc"] = data[12];
			requestMap["modelList["+index+"].bjmc"] = data[13];
			requestMap["modelList["+index+"].xkrs"] = data[14];
			requestMap["modelList["+index+"].jsxm"] = data[15];
			requestMap["modelList["+index+"].lxdh"] = data[16];
			requestMap["modelList["+index+"].bh"] = data[18];
			requestMap["modelList["+index+"].njmc"] = data[19];
			requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		});
		var map = {};
		map["kzlx"] ="dy";
		map["xnm"] =requestMap["modelList[0].xnm"];
		map["xqm"] =requestMap["modelList[0].xqm"];
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: map,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
					   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelSlhlBjkb.html",requestMap).submit(); 
				   }
			   }
			});
	});
	
	$("#dkbplscexcel_btn").on("click",function(){
		var selrows = $("#tabGrid").getGridParam("selarrrow");
		if($("#bot_ul").children().length == 0){
		//if(selrows.length == 0){
			$.alert($.i18n.get("alert6"));
			return false;
		}
		var requestMap = {
			"kblx"      : 'bj',
			"xszd.sj" 	: xszd.sj,
			"xszd.cd" 	: xszd.cd,
			"xszd.js" 	: xszd.js,
			"xszd.jszc" : xszd.jszc,
			"xszd.jxb" 	: xszd.jxb,
			"xszd.jxbzc" 	: xszd.jxbzc,
			"xszd.kcxzjc" 	: xszd.kcxzjc,
			"xszd.jxbrs" 	: xszd.jxbrs,
			"xszd.zyfx" : xszd.zyfx,
			"xszd.xkrs" : xszd.xkrs,
			"xszd.khfs" : xszd.khfs,
			"xszd.ksfs" : xszd.ksfs,
			"xszd.xkbz" : xszd.xkbz,
			"xszd.kcxszc" : xszd.kcxszc,
			"xszd.zhxs" : xszd.zhxs,
			"xszd.zxs" : xszd.zxs,
			"xszd.xf"  : xszd.xf,
			"xszd.zzrl"  : xszd.zzrl,
			"xszd.xq"  : xszd.xq,
			"xszd.skfsmc"  : xszd.skfsmc,
			"xszd.zxxx"  : xszd.zxxx,
			"xszd.zfj"  : xszd.zfj,
			"xszd.kch"  : xszd.kch,
			"xszd.jgh":xszd.jgh,
			"xszd.kclbmc":xszd.kclbmc,
			"xszd.kczxs":xszd.kczxs
			
		};

		$("#bot_ul").children().each(function(index, item){
			var datas = $(this).attr("data");
			var data = datas.split("~");
			if(index == 0){
				requestMap["xnm"] = data[2];
				requestMap["xqm"] = data[3];
				requestMap["xnmc"] = data[4];
				requestMap["xqmmc"] = data[5];
				requestMap["xqh_id"] = data[10];
			}		
			requestMap["modelList["+index+"].tjkbzdm"] = data[0];
			requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
			requestMap["modelList["+index+"].xnm"] = data[2];
			requestMap["modelList["+index+"].xqm"] = data[3];
			requestMap["modelList["+index+"].xnmc"] = data[4];
			requestMap["modelList["+index+"].xqmmc"] = data[5];
			requestMap["modelList["+index+"].njdm_id"] =data[6];
			requestMap["modelList["+index+"].zyh_id"] = data[7];
			requestMap["modelList["+index+"].bh_id"] = data[8];
			requestMap["modelList["+index+"].tjkbmc"] = data[9];
			requestMap["modelList["+index+"].xqh_id"] = data[10];
			requestMap["modelList["+index+"].zymc"] = data[11];
			requestMap["modelList["+index+"].jgmc"] = data[12];
			requestMap["modelList["+index+"].bjmc"] = data[13];
			requestMap["modelList["+index+"].xkrs"] = data[14];
			requestMap["modelList["+index+"].jsxm"] = data[15];
			requestMap["modelList["+index+"].lxdh"] = data[16];
			requestMap["modelList["+index+"].bh"] = data[18];
			requestMap["modelList["+index+"].njmc"] = data[19];
			requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
		});
		var map = {};
		map["kzlx"] ="dy";
		map["xnm"] =requestMap["modelList[0].xnm"];
		map["xqm"] =requestMap["modelList[0].xqm"];
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: map,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
					   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExceldkb.html",requestMap).submit(); 
				   }
			   }
			});
	});
	
	/**
	 * 杨凌职业技术学院
	 */
	$("#qxkbplscexcel_btn").on("click",function(){
		var map = {};
		map["kzlx"] ="dy";
		var selrows = $("#tabGrid").getGridParam("selarrrow");
		var requestMap = {};
		if(selrows.length == 0){
			var map = {};
			map["xnm"] = $('#xnm').val();
			map["xqm"] = $('#xqm').val();
			requestMap["xnm"] = $('#xnm').val();
			requestMap["xqm"] = $('#xqm').val();
			requestMap["xnmc"] = $("#xnm").find("option:selected").text();
			requestMap["xqmmc"] = $("#xqm").find("option:selected").text();
			requestMap["xqh_id"] = $('#xqh_id').val();
			requestMap["njdm_id"] = $('#njdm_id').val();
			requestMap["jg_id"] = $('#jg_id').val();
			requestMap["zyh_id"] = $('#zyh_id').val();
			requestMap["zyfx_id"] = $("#zyfx_id").val()||'';
			requestMap["bh_id"] = $('#bh_id').val()||'';
			requestMap["xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
			requestMap["pyccdm"] = $("#pyccdm").val()||'';
			requestMap["kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
			requestMap["sfzhsjk"] = $("#sfzhsjk").val()||'';
			requestMap["kbsjlyqz"] = $("#kbsjlyqz").val()||'';
			requestMap["zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
			requestMap["zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
			requestMap["yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val()
		}else{
			$("#bot_ul").children().each(function(index, item){
				var datas = $(this).attr("data");
				var data = datas.split("~");
				if(index == 0){
					requestMap["xnm"] = data[2];
					requestMap["xqm"] = data[3];
					requestMap["xnmc"] = data[4];
					requestMap["xqmmc"] = data[5];
					requestMap["xqh_id"] = data[10];
				}		
				requestMap["modelList["+index+"].tjkbzdm"] = data[0];
				requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
				requestMap["modelList["+index+"].xnm"] = data[2];
				requestMap["modelList["+index+"].xqm"] = data[3];
				requestMap["modelList["+index+"].xnmc"] = data[4];
				requestMap["modelList["+index+"].xqmmc"] = data[5];
				requestMap["modelList["+index+"].njdm_id"] =data[6];
				requestMap["modelList["+index+"].zyh_id"] = data[7];
				requestMap["modelList["+index+"].bh_id"] = data[8];
				requestMap["modelList["+index+"].tjkbmc"] = data[9];
				requestMap["modelList["+index+"].xqh_id"] = data[10];
				requestMap["modelList["+index+"].zymc"] = data[11];
				requestMap["modelList["+index+"].jgmc"] = data[12];
				requestMap["modelList["+index+"].bjmc"] = data[13];
				requestMap["modelList["+index+"].xkrs"] = data[14];
				requestMap["modelList["+index+"].jsxm"] = data[15];
				requestMap["modelList["+index+"].lxdh"] = data[16];
				requestMap["modelList["+index+"].bh"] = data[18];
				requestMap["modelList["+index+"].njmc"] = data[19];
				requestMap["modelList["+index+"].jgdm"] = data[20];
				requestMap["modelList["+index+"].zyh"] = data[21];
				requestMap["modelList["+index+"].njdm"] = data[22];
				requestMap["modelList["+index+"].xsxyxh"] = data[23];
				requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
				requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
			});
			map["xnm"] =requestMap["modelList[0].xnm"];
			map["xqm"] =requestMap["modelList[0].xqm"];
		}
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: map,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
					   $.buildForm("downForm",_path+"/kbdy/bjkbdy_cxDcExcelQxkb10966.html",requestMap).submit(); 
				   }
			   }
			});
	});
	
	$("#bjkbfkdypdf_btn").on("click",function(){
		if($("#bot_ul").children().length == 0){
			$.alert($.i18n.get("alert6"));
			return false;
		}
		var requestMap = {
			"xszd.sj" 	: xszd.sj,
			"xszd.cd" 	: xszd.cd,
			"xszd.js" 	: xszd.js,
			"xszd.jszc" : xszd.jszc,
			"xszd.jxb" 	: xszd.jxb,
			"xszd.jxbzc" 	: xszd.jxbzc,
			"xszd.kcxzjc" 	: xszd.kcxzjc,
			"xszd.jxbrs" 	: xszd.jxbrs,
			"xszd.zyfx" : xszd.zyfx,
			"xszd.xkrs" : xszd.xkrs,
			"xszd.khfs" : xszd.khfs,
			"xszd.ksfs" : xszd.ksfs,
			"xszd.xkbz" : xszd.xkbz,
			"xszd.kcxszc" : xszd.kcxszc,
			"xszd.zhxs" : xszd.zhxs,
			"xszd.zxs" : xszd.zxs,
			"xszd.xf"  : xszd.xf,
			"xszd.zzrl"  : xszd.zzrl,
			"xszd.xq"  : xszd.xq,
			"xszd.skfsmc"  : xszd.skfsmc,
			"xszd.zxxx"  : xszd.zxxx,
			"xszd.zfj"  : xszd.zfj,
			"xszd.kch"  : xszd.kch,
			"xszd.kczxs":xszd.kczxs,
			"xszd.zyhxkcbj":xszd.zyhxkcbj,
			"xszd.cdlbmc":xszd.cdlbmc,
			"xszd.ktmc":xszd.ktmc,
			"xszd.skpthyh":xszd.skpthyh,
			"xszd.jtskwz":xszd.jtskwz,
			"xszd.qqqh":xszd.qqqh,
			"xszd.jgh":xszd.jgh,
			"xszd.kclbmc":xszd.kclbmc,
			"xszd.zzmm":xszd.zzmm
		};
		$("#bot_ul").children().each(function(index, item){
			var datas = $(this).attr("data");
			var data = datas.split("~");
			if(index == 0){
				requestMap["xnm"] = data[2];
				requestMap["xqm"] = data[3];
			}		
			requestMap["modelList["+index+"].tjkbzdm"] = data[0];
			requestMap["modelList["+index+"].tjkbzxsdm"] = data[1];
			requestMap["modelList["+index+"].xnm"] = data[2];
			requestMap["modelList["+index+"].xqm"] = data[3];
			requestMap["modelList["+index+"].xnmc"] = data[4];
			requestMap["modelList["+index+"].xqmmc"] = data[5];
			requestMap["modelList["+index+"].njdm_id"] =data[6];
			requestMap["modelList["+index+"].zyh_id"] = data[7];
			requestMap["modelList["+index+"].bh_id"] = data[8];
			requestMap["modelList["+index+"].tjkbmc"] = data[9];
			requestMap["modelList["+index+"].xqh_id"] = data[10];
			requestMap["modelList["+index+"].zymc"] = data[11];
			requestMap["modelList["+index+"].jgmc"] = data[12];
			requestMap["modelList["+index+"].bjmc"] = data[13];
			requestMap["modelList["+index+"].xkrs"] = data[14];
			requestMap["modelList["+index+"].jsxm"] = data[15];
			requestMap["modelList["+index+"].lxdh"] = data[16];
			requestMap["modelList["+index+"].bh"] = data[18];
			requestMap["modelList["+index+"].njmc"] = data[19];
			requestMap["modelList["+index+"].zs"] = typeof($('#zs').val())=='undefined'?"":$('#zs').val();
			requestMap["modelList["+index+"].xsdm"] = typeof($('#xsdm').val())=='undefined'?"":$('#xsdm').val();
			requestMap["modelList["+index+"].zxszjjs"] = $("#zxszjjs:checked").val()==1?true:false;
			requestMap["modelList["+index+"].akcxqjchb"] = $("#akcxqjchb:checked").val()==1?true:false;
			requestMap["modelList["+index+"].kclxdm"] = typeof($('#kclxdm').val())=='undefined'?"":$('#kclxdm').val();
			requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
			requestMap["modelList["+index+"].kbsjlyqz"] = typeof($('#kbsjlyqz').val())=='undefined'?"":$('#kbsjlyqz').val();
			requestMap["modelList["+index+"].yf"] = typeof($('#yf').val())=='undefined'?"":$('#yf').val();
			requestMap["modelList["+index+"].kclbdm"] = typeof($('#kclbdm').val())=='undefined'?"":$('#kclbdm').val();
			requestMap["modelList["+index+"].sfcxxqh"] = $.founded($('#xqh_id').val())?"1":"0";
		});
		var map = {};
		map["kzlx"] ="dy";
		map["xnm"] =requestMap["modelList[0].xnm"];
		map["xqm"] =requestMap["modelList[0].xqm"];
		$.ajax({
			   type: "POST",
			   url: _path+"/kbdy/bjkbdy_cxXnxqsfkz.html",
			   async: false,
			   data: map,
			   datatype: "json",
			   success: function(data){
				   if(data.indexOf("true") > -1){
					   $.alert($.i18n.get("alert4"));
						flag=true;
				   }else{
						//构建form
						$.buildForm("downForm",_path+"/kbdy/bjkbdy_shcpdfFkdyBjkb.html?doType=table",requestMap).submit();
						
				   }
			   }
			});
		
	});
	
});