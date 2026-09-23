'use strict';
// Shared by the Mac library window and video player (same origin, same setting).
window.KotobaHover=(()=>{
  const key='desktop.hoverModifier';
  const choices={none:{label:'No key',prop:null,key:null},shift:{label:'Shift',prop:'shiftKey',key:'Shift'},
    alt:{label:'Option',prop:'altKey',key:'Alt'},ctrl:{label:'Control',prop:'ctrlKey',key:'Control'},
    meta:{label:'Command',prop:'metaKey',key:'Meta'}};
  const valid=value=>Object.prototype.hasOwnProperty.call(choices,value);
  function get(){try{const value=localStorage.getItem(key);if(valid(value))return value;}catch(e){}return 'shift';}
  function set(value){if(!valid(value))return;try{localStorage.setItem(key,value);}catch(e){}}
  function matches(event){const choice=choices[get()];return !choice.prop||!!event[choice.prop];}
  function isKey(event){const name=choices[get()].key;return !!name&&event.key===name;}
  function label(){return choices[get()].label;}
  function clickLookup(){try{return localStorage.getItem('desktop.clickLookup')==='true';}catch(e){return false;}}
  function setClickLookup(value){try{localStorage.setItem('desktop.clickLookup',String(!!value));}catch(e){}}
  return {get,set,matches,isKey,label,choices,clickLookup,setClickLookup};
})();
