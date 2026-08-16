type MiPass = Partial<{
    qs: string;
    _sign: string;
    callback: string;
    location: string;
    ssecurity: string;
    passToken: string;
    nonce: string;
    userId: string;
    cUserId: string;
    psecurity: string;
}>;
interface MiIOTDevice {
    did: string;
    token: string;
    name: string;
    localip: string;
    mac: string;
    ssid: string;
    bssid: string;
    model: string;
    isOnline: boolean;
    desc: string;
    uid: number;
    pd_id: number;
    rssi: number;
}
interface MinaDevice {
    deviceId: string;
    deviceID: string;
    serialNumber: string;
    name: string;
    alias: string;
    presence: "offline" | "online";
    miotDID: string;
    hardware: string;
    deviceSNProfile: string;
    deviceProfile: string;
    brokerEndpoint: string;
    brokerIndex: number;
    mac: string;
    ssid: string;
}
interface MiAccount {
    sid: "xiaomiio" | "micoapi";
    deviceId: string;
    userId: string;
    password: string;
    pass?: MiPass;
    serviceToken?: string;
    did?: string;
    device?: MinaDevice | MiIOTDevice;
}
interface AnswerLLM {
    bitSet: [number, number, number, number];
    type: "LLM";
    llm: {
        bitSet: [number, number];
        text: string;
    };
}
interface AnswerTTS {
    bitSet: [number, number, number, number];
    type: "TTS";
    tts: {
        bitSet: [number, number];
        text: string;
    };
}
interface AnswerAudio {
    bitSet: [number, number, number, number];
    type: "AUDIO";
    audio: {
        bitSet: [number, number];
        audioInfoList: {
            bitSet: [number, number, number, number];
            title: string;
            artist: string;
            cpName: string;
        }[];
    };
}
type Answer = AnswerLLM | AnswerTTS | AnswerAudio;
/**
 * 已经执行了的动作（比如调节音量等），answer 为空
 */
interface MiConversations {
    bitSet: [number, number, number];
    records: {
        bitSet: [number, number, number, number, number];
        answers: Answer[];
        time: number;
        query: string;
        requestId: string;
    }[];
    nextEndTime: number;
}

type MinaMiAccount = MiAccount & {
    device: MinaDevice;
};
declare class MiNA {
    account: MinaMiAccount;
    constructor(account: MinaMiAccount);
    static getDevice(account: MinaMiAccount): Promise<MinaMiAccount>;
    private static __callMina;
    private _callMina;
    ubus(scope: string, command: string, message?: any): Promise<any>;
    getDevices(): Promise<any>;
    getStatus(): Promise<{
        volume: number;
        status: "idle" | "playing" | "paused" | "stopped" | "unknown";
        media_type?: number;
        loop_type?: number;
    } | undefined>;
    getVolume(): Promise<number | undefined>;
    setVolume(volume: number): Promise<boolean>;
    play(options?: {
        tts?: string;
        url?: string;
    }): Promise<boolean>;
    pause(): Promise<boolean>;
    playOrPause(): Promise<boolean>;
    stop(): Promise<boolean>;
    /**
     * 注意：
     * 只拉取用户主动请求，设备被动响应的消息，
     * 不包含设备主动回应用户的消息。
     *
     * - 从游标处由新到旧拉取
     * - 结果包含游标消息本身
     * - 消息列表从新到旧排序
     */
    getConversations(options?: {
        limit?: number;
        timestamp?: number;
    }): Promise<MiConversations | undefined>;
}

type MiIOTMiAccount = MiAccount & {
    device: MiIOTDevice;
};
declare class MiIOT {
    account: MiIOTMiAccount;
    constructor(account: MiIOTMiAccount);
    static getDevice(account: MiIOTMiAccount): Promise<MiIOTMiAccount>;
    private static __callMiIOT;
    private _callMiIOT;
    rpc(method: string, params: any, id?: number): Promise<any>;
    /**
     * - datasource=1  优先从服务器缓存读取，没有读取到下发rpc；不能保证取到的一定是最新值
     * - datasource=2  直接下发rpc，每次都是设备返回的最新值
     * - datasource=3  直接读缓存；没有缓存的 code 是 -70xxxx；可能取不到值
     */
    private _callMiotSpec;
    getDevices(getVirtualModel?: boolean, getHuamiDevices?: number): Promise<any>;
    getProperty(scope: number, property: number): Promise<any>;
    setProperty(scope: number, property: number, value: any): Promise<boolean>;
    doAction(scope: number, action: number, args?: any): Promise<boolean>;
}

interface MiServiceConfig {
    userId: string;
    password: string;
    did?: string;
    enableTrace?: boolean;
    /**
     * 网络请求超时时间，单位毫秒，默认 3000（3 秒）
     */
    timeout?: number;
}
declare function getMiIOT(config: MiServiceConfig): Promise<MiIOT | undefined>;
declare function getMiNA(config: MiServiceConfig): Promise<MiNA | undefined>;

export { MiIOT, MiNA, type MiServiceConfig, getMiIOT, getMiNA };
