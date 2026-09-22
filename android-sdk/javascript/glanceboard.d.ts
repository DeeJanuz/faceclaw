export type GlanceWidgetDeclaration = {id:string;label:string;kind:'list'|'scene';rows:1|2;refreshMs:number;uses?:('battery'|'weather'|'time-format')[]};
export type GlanceWidgetRegistry = {version:1;widgets:GlanceWidgetDeclaration[]};
export type GlanceCommand = {op:'text';x:number;y:number;width:number;text:string;value:number}|{op:'rect';x:number;y:number;width:number;height:number;value:number}|{op:'bitmap';x:number;y:number;width:number;height:number;bits:string;value:number};
export type GlanceWidgetContent = {version:2;widgetId:string;expiresAt:number} & ({title:string;emptyText:string;entries:{id:string;title:string;detail:string;expiresAt?:number}[]}|{commands:GlanceCommand[]});
export type GlanceWidgetRequest = {version:2;widgetId:string;width:288;height:144|288;context:{battery?:{headset:number|null;headsetCharging:boolean|null};weather?:{phase:string;locationName:string;current:{temperatureF:number|null;description:string}|null;lastUpdatedMs:number|null};timeFormat?:string}};
export function validateWidgetRegistry(value:unknown):GlanceWidgetRegistry;
export function validateWidgetContent(value:unknown,registry:GlanceWidgetRegistry,now?:number):GlanceWidgetContent;
export class GlanceCanvas {
 constructor(widgetId:string,expiresAt?:number);
 text(x:number,y:number,text:string,width?:number,value?:number):this;
 rect(x:number,y:number,width:number,height:number,value?:number):this;
 bitmap(x:number,y:number,width:number,height:number,grayPixels:ArrayLike<number>,value?:number):this;
 build():GlanceWidgetContent;
}
