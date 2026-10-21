'use client';

import React, { useEffect, useState, useRef } from 'react';
import { useParams, useSearchParams, useRouter } from 'next/navigation';
import { movieApi, API_BASE_URL } from '@/lib/api';
import axios from 'axios';
import { 
  ChevronLeft, Settings, Maximize, Volume2, 
  Play, Pause, SkipForward, Info, Download,
  Activity, ShieldCheck, Zap, Monitor,
  User, LogOut, UserPlus, Send
} from 'lucide-react';
import dynamic from 'next/dynamic';

const VideoPlayer = dynamic(() => import('@/components/VideoPlayer'), { 
  ssr: false,
  loading: () => (
    <div className="w-full h-full bg-zinc-900 rounded-[2.5rem] flex flex-col items-center justify-center gap-4 border border-white/5">
        <Activity className="w-10 h-10 text-red-600 animate-spin" />
        <p className="text-[10px] font-black uppercase tracking-widest text-zinc-500">Loading Engine...</p>
    </div>
  )
});

export default function WatchPage() {
  const { id } = useParams();
  const searchParams = useSearchParams();
  const router = useRouter();
  
  const season = parseInt(searchParams.get('s') || '1');
  const episode = parseInt(searchParams.get('e') || '1');

  const [selectedQuality, setSelectedQuality] = useState('720P');
  const [subtitlesOn, setSubtitlesOn] = useState(false);
  const [selectedSubId, setSelectedSubId] = useState<string | null>(null);
  const [subtitles, setSubtitles] = useState<any[]>([]);
  const [showSubModal, setShowSubModal] = useState(false);
  
  const [streamData, setStreamData] = useState<any>(null);
  const [movie, setMovie] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [showPlayer, setShowPlayer] = useState(false);
  const [resumeTime, setResumeTime] = useState(0);
  const [isTranscoding, setIsTranscoding] = useState(false);
  const [playerError, setPlayerError] = useState<string | null>(null);

  // Auth State
  const [userInfo, setUserInfo] = useState<any>(null);
  const [showLoginModal, setShowLoginModal] = useState(false);
  const [authMode, setAuthMode] = useState<'login' | 'register'>('login');
  const [loginForm, setLoginForm] = useState({ account: '', password: '', otp: '' });
  const [loginLoading, setLoginLoading] = useState(false);
  const [otpSent, setOtpSent] = useState(false);
  const [otpLoading, setOtpLoading] = useState(false);

  useEffect(() => {
    if (id) {
       fetchStream();
       fetchMetadata();
       fetchSubtitles();
       checkAuth();
    }
  }, [id, season, episode, selectedQuality]);

  const checkAuth = async () => {
    try {
      const res = await movieApi.getUserInfo();
      if (res.logged_in) {
        setUserInfo(res.user);
      } else {
        setUserInfo(null);
      }
    } catch (e) {}
  };

  const handleRequestOtp = async () => {
     if (!loginForm.account) return alert('Email/Phone is required');
     setOtpLoading(true);
     try {
        const res = await movieApi.requestOtp(loginForm.account, 1, authMode === 'register' ? 1 : 2);
        if (res.status === 'success') {
           setOtpSent(true);
           alert('Verification code sent! Please check your inbox/SMS.');
        }
     } catch (err: any) {
        alert(err.response?.data?.detail || 'Failed to send OTP');
     }
     setOtpLoading(false);
  };

  const handleAuth = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoginLoading(true);
    try {
      if (authMode === 'login') {
        const res = await movieApi.login(loginForm.account, loginForm.password);
        if (res.status === 'success') {
          setUserInfo(res.user);
          setShowLoginModal(false);
          fetchStream();
        }
      } else {
        if (!loginForm.otp) return alert('Verification code is required for registration');
        const res = await movieApi.register(loginForm.account, loginForm.password, loginForm.otp);
        if (res.status === 'success') {
          alert('Registration successful! Please login.');
          setAuthMode('login');
          setOtpSent(false);
        }
      }
    } catch (err: any) {
      alert(err.response?.data?.detail || 'Authentication failed');
    }
    setLoginLoading(false);
  };

  const handleLogout = async () => {
     try {
        await movieApi.logout();
        setUserInfo(null);
        fetchStream();
     } catch (e) {}
  };

  const fetchMetadata = async () => {
    try {
      const res = await movieApi.getDetail(id as string);
      if (res.code === 0) setMovie(res.data);
    } catch (e) {}
  };

  const normalizeSubtitle = (sub: any) => {
    const rawUrl = sub.url || sub.subPath || '';
    // Proxy ALL subtitles to avoid taining the canvas during screenshots/thumbnails
    const proxiedUrl = rawUrl ? `${API_BASE_URL}/sub-proxy?u=${encodeURIComponent(rawUrl)}` : '';
    
    return {
        sid: sub.id || sub.sid || Math.random().toString(),
        language: sub.lanName || sub.language || sub.lan || 'Unknown',
        subPath: proxiedUrl
    };
  };

  const fetchSubtitles = async () => {
     try {
        const res = await movieApi.getSubtitles(id as string, season, episode);
        const list1 = (res.data?.list || []).map(normalizeSubtitle);
        setSubtitles(list1);
     } catch (e) {}
  };


  const fetchStream = async () => {
    setLoading(true);
    try {
      const res = await movieApi.getStream(id as string, season, episode, selectedQuality);
      setStreamData(res);
      
      if (res.streamId) {
          try {
              const hist = await movieApi.getHistoryPosition(id as string, res.streamId);
              const pos = hist.data?.position || hist.data?.seeTime;
              if (pos && pos > 5000) {
                  setResumeTime(Math.floor(pos / 1000));
              }
          } catch (e) {}
      }

      movieApi.markHaveSeen(id as string, 1000, 3600).catch(()=>{});
      
      if (res.subtitles && res.subtitles.length > 0) {
          setSubtitles(prev => {
             const mapped = res.subtitles.map(normalizeSubtitle);
             const existing = new Set(prev.map(p => p.sid));
             const newSubs = mapped.filter((s: any) => !existing.has(s.sid));
             return [...newSubs, ...prev];
          });
      }
    } catch (e) {}
    setLoading(false);
  };

  const handleTranscodeFailover = async () => {
    setIsTranscoding(true);
    setPlayerError(null);
    try {
        const compatUrl = `${API_BASE_URL}/play-compat/${id}?season=${season}&episode=${episode}&quality=${selectedQuality}`;
        setStreamData({
            url: compatUrl,
            cookie: '', 
            isTranscoded: true
        });
    } catch (e) {
        console.error("Transcode failover failed:", e);
    } finally {
        setIsTranscoding(false);
    }
  };

  const handlePlayerError = (error: any) => {
    // If we're already using the transcode pipe, don't show another error or try to transcode again
    if (streamData?.isTranscoded) return;

    console.warn("Codec incompatibility detected. Swapping to High Compatibility (Transcode) Mode...");
    handleTranscodeFailover();
  };

  const handleProgress = (time: number) => {
      // Throttled history saving could be added here
      if (Math.floor(time) % 10 === 0 && streamData?.streamId) {
          movieApi.saveHistoryPosition(id as string, streamData.streamId, Math.floor(time * 1000)).catch(()=>{});
      }
  };

  const downloadStream = () => {
     const movieTitle = encodeURIComponent(movie?.title || 'Movie');
     const downloadUrl = `${API_BASE_URL}/download/${id}?season=${season}&episode=${episode}&quality=${selectedQuality}&title=${movieTitle}`;
     
     // PRO-LEVEL SILENT DOWNLOAD
     const link = document.createElement('a');
     link.href = downloadUrl;
     link.setAttribute('download', `${movie?.title || 'Movie'}.mp4`);
     document.body.appendChild(link);
     link.click();
     document.body.removeChild(link);
  };

  const launchExternalPlayer = async (player: 'vlc' | 'mpv') => {
    if (!streamData?.url) {
        alert("No active stream resolved. If this is a VIP show, please Sign In first.");
        return;
    }
    try {
        let startTime = resumeTime;
        let subUrl: string | undefined = undefined;
        if (subtitlesOn && subtitles.length > 0) {
            const activeSub = selectedSubId ? subtitles.find(s => s.sid === selectedSubId) : subtitles[0];
            if (activeSub) subUrl = activeSub.subPath;
        }

        await axios.post(`${API_BASE_URL}/launch-player`, null, {
            params: {
                player: player,
                url: streamData.url,
                cookie: streamData.cookie,
                subject_id: id,
                season: movie?.subjectType === 2 ? season : undefined,
                episode: movie?.subjectType === 2 ? episode : undefined,
                title: movie?.title,
                cover: movie?.cover,
                start_time: startTime,
                subtitle_url: subUrl,
                duration: Math.floor((streamData.runtime ? parseInt(streamData.runtime) : (movie?.runtime ? parseInt(movie.runtime) : 0)) * 60)
            }
        });
    } catch (e) {
        alert("Failed to launch player. Please ensure it is installed.");
    }
  };

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen bg-black gap-6">
        <Activity className="w-12 h-12 text-red-600 animate-spin" />
        <p className="text-zinc-500 font-black uppercase tracking-[0.3em] text-xs font-mono">Decrypting Mirror...</p>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-zinc-950 flex flex-col relative overflow-hidden text-white font-sans selection:bg-red-600 selection:text-white">
      {/* Cinematic Background Blur */}
      <div className="absolute inset-0 opacity-20 pointer-events-none">
         <img src={movie?.cover} className="w-full h-full object-cover blur-[120px] scale-150" alt="" />
      </div>

      <div className="z-10 flex flex-col items-center flex-1 px-6 py-12 max-w-7xl mx-auto w-full">
         
         {/* Metadata Header */}
         <div className="w-full flex items-start justify-between mb-12">
            <div className="space-y-4">
                <button onClick={() => router.back()} className="p-4 bg-white/5 rounded-2xl hover:bg-red-600 transition-all border border-white/10 group backdrop-blur-3xl shadow-xl">
                   <ChevronLeft className="w-6 h-6 group-hover:-translate-x-1 transition-transform" />
                </button>
                <div className="space-y-1">
                    <h1 className="text-5xl md:text-7xl font-black uppercase italic tracking-tighter drop-shadow-2xl text-transparent bg-clip-text bg-gradient-to-b from-white to-zinc-500">
                       {movie?.title}
                    </h1>
                    <div className="flex items-center gap-4 text-[10px] font-black text-red-500 uppercase tracking-[0.2em] bg-red-500/10 px-4 py-2 rounded-full w-fit border border-red-500/20 backdrop-blur-xl">
                       <Zap className="w-3 h-3 fill-red-500" />
                       {movie?.subjectType === 2 ? `Season ${season} • Episode ${episode}` : 'Feature Film'}
                    </div>
                </div>
            </div>

            <div className="flex flex-col items-end gap-3 text-right">
                {userInfo ? (
                    <div className="flex items-center gap-3 bg-white/5 border border-white/10 rounded-2xl px-4 py-2">
                        <div className="w-6 h-6 rounded-full bg-yellow-500 text-black flex items-center justify-center font-black text-[10px]">
                           {userInfo.mail ? userInfo.mail[0].toUpperCase() : 'U'}
                        </div>
                        <span className="text-[10px] font-black uppercase tracking-widest text-zinc-300">{userInfo.mail || userInfo.phone || 'Member'}</span>
                        <button onClick={handleLogout} className="p-1 text-zinc-500 hover:text-red-500 transition-colors">
                           <LogOut className="w-4 h-4" />
                        </button>
                    </div>
                ) : (
                    <button 
                       onClick={() => setShowLoginModal(true)}
                       className="px-6 py-3 bg-yellow-500 hover:bg-yellow-600 text-black rounded-2xl text-[10px] font-black uppercase tracking-widest transition-all shadow-lg shadow-yellow-500/10"
                    >
                       Sign In
                    </button>
                )}
                <div className="hidden md:flex flex-col items-end gap-1">
                    <p className="text-[8px] font-black text-zinc-600 uppercase tracking-widest leading-none">Global Load Balancer</p>
                    <p className="text-green-500 text-[8px] font-black uppercase tracking-widest leading-none">Cluster-6 Online</p>
                </div>
            </div>
         </div>

         {/* MAIN PLAYER ZONE */}
         <div className="w-full aspect-video relative group mb-12">
            {showPlayer ? (
                <div className="w-full h-full animate-in fade-in zoom-in-95 duration-700">
                    {streamData ? (
                        <VideoPlayer 
                            url={streamData.url} 
                            cookie={streamData.cookie} 
                            poster={movie?.cover} 
                            duration={Math.floor(
                                (streamData.runtime ? parseInt(streamData.runtime) : (movie?.runtime ? parseInt(movie.runtime) : 0)) * 60
                            )}
                            subtitleUrl={subtitlesOn ? (selectedSubId ? subtitles.find(s => s.sid === selectedSubId)?.subPath : subtitles[0]?.subPath) : null}
                            startTime={resumeTime}
                            onProgress={handleProgress}
                            onError={handlePlayerError}
                        />
                    ) : (
                        <div className="w-full h-full bg-zinc-900 rounded-[3rem] flex flex-col items-center justify-center gap-4 text-center p-12 border border-red-600/30">
                            <Activity className="w-12 h-12 text-red-600" />
                            <div className="space-y-2">
                                <h3 className="text-xl font-black uppercase italic tracking-tighter text-red-600">Stream Blocked</h3>
                                <p className="text-[10px] text-zinc-500 font-black uppercase tracking-widest leading-relaxed max-w-xs">
                                    This title requires a premium session or active login.
                                </p>
                                <button 
                                   onClick={() => setShowLoginModal(true)}
                                   className="mt-4 px-8 py-3 bg-yellow-500 hover:bg-yellow-600 text-black rounded-2xl text-[10px] font-black uppercase tracking-widest transition-all shadow-lg"
                                >
                                   Sign In Now
                                </button>
                            </div>
                        </div>
                    )}
                </div>
            ) : (
                <button 
                  onClick={() => setShowPlayer(true)}
                  className="w-full h-full bg-zinc-900 border border-white/10 rounded-[3rem] overflow-hidden group/btn relative flex flex-col items-center justify-center gap-8 shadow-2xl transition-all hover:border-red-600/50"
                >
                   <div className="absolute inset-0 bg-gradient-to-br from-red-600/10 to-transparent opacity-0 group-hover/btn:opacity-100 transition-opacity duration-500" />
                   <img src={movie?.cover} className="absolute inset-0 w-full h-full object-cover opacity-10 blur-xl group-hover/btn:scale-110 transition-transform duration-1000" />
                   
                   <div className="w-32 h-32 bg-red-600 rounded-[2.5rem] flex items-center justify-center shadow-[0_0_50px_rgba(225,29,72,0.4)] group-hover/btn:scale-110 group-hover/btn:rotate-12 transition-all duration-500 z-10">
                      <Play className="w-16 h-16 fill-white text-white ml-2" />
                   </div>
                   
                   <div className="space-y-2 z-10 text-center">
                      <h2 className="text-3xl font-black uppercase italic tracking-tighter">Initialize Cloud Player</h2>
                      <p className="text-[10px] font-black text-zinc-500 uppercase tracking-[0.3em]">Zero Buffering • Safe-Path Encrypted</p>
                   </div>

                   {resumeTime > 0 && (
                       <div className="absolute bottom-12 px-6 py-3 bg-white/5 border border-white/10 rounded-2xl backdrop-blur-3xl flex items-center gap-3">
                           <Activity className="w-4 h-4 text-green-500" />
                           <span className="text-[10px] font-black uppercase tracking-widest text-zinc-400">Resume from {Math.floor(resumeTime/60)}m {resumeTime%60}s</span>
                       </div>
                   )}
                </button>
            )}
         </div>

         {/* CONTROLS & UTILITIES */}
         <div className="w-full grid grid-cols-1 md:grid-cols-3 gap-6 mb-12">
            {/* Engine Selection */}
            <div className="md:col-span-2 grid grid-cols-2 gap-4">
               <button 
                  onClick={() => launchExternalPlayer('vlc')}
                  className="flex items-center gap-6 p-6 bg-zinc-900 border border-white/5 rounded-[2.5rem] hover:border-orange-500/50 transition-all group overflow-hidden relative shadow-xl"
               >
                  <div className="absolute inset-0 bg-gradient-to-br from-orange-600/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity" />
                  <div className="w-16 h-16 bg-orange-600 rounded-2xl flex items-center justify-center flex-shrink-0 shadow-lg group-hover:rotate-12 transition-transform">
                     <Monitor className="w-8 h-8 text-white" />
                  </div>
                  <div className="text-left">
                     <p className="text-[10px] text-zinc-500 font-black uppercase tracking-widest mb-1">External Engine</p>
                     <p className="text-xl font-black uppercase italic tracking-tighter">VLC DESKTOP</p>
                  </div>
               </button>

               <button 
                  onClick={() => launchExternalPlayer('mpv')}
                  className="flex items-center gap-6 p-6 bg-zinc-900 border border-white/5 rounded-[2.5rem] hover:border-green-500/50 transition-all group overflow-hidden relative shadow-xl"
               >
                  <div className="absolute inset-0 bg-gradient-to-br from-green-600/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity" />
                  <div className="w-16 h-16 bg-green-600 rounded-2xl flex items-center justify-center flex-shrink-0 shadow-lg group-hover:-rotate-12 transition-transform">
                     <Activity className="w-8 h-8 text-white" />
                  </div>
                  <div className="text-left">
                     <p className="text-[10px] text-zinc-500 font-black uppercase tracking-widest mb-1">Pro Engine</p>
                     <p className="text-xl font-black uppercase italic tracking-tighter">MPV STACK</p>
                  </div>
               </button>
            </div>

            {/* Quality Swiper */}
            <div className="bg-zinc-900 p-6 rounded-[2.5rem] border border-white/5 shadow-xl flex flex-col justify-center gap-4">
                <div className="flex items-center justify-between">
                    <div className="text-left">
                        <p className="text-[10px] text-zinc-500 font-black uppercase tracking-widest mb-1 leading-none">Resolution</p>
                        <p className="text-xl font-black uppercase italic tracking-tighter">{selectedQuality}</p>
                    </div>
                    <Settings className="w-5 h-5 text-zinc-700" />
                </div>
                <div className="flex gap-2">
                     {['480P', '720P', '1080P'].map((q) => (
                        <button 
                          key={q} 
                          onClick={() => { setSelectedQuality(q); setShowPlayer(false); }}
                          className={`flex-1 py-3 rounded-xl text-[10px] font-black transition-all border border-white/5 ${
                            selectedQuality === q ? 'bg-red-600 text-white shadow-lg shadow-red-600/20' : 'bg-white/5 text-zinc-500 hover:bg-white/10'
                          }`}
                        >
                           {q}
                        </button>
                     ))}
                </div>
            </div>
         </div>

         {/* SUBTITLE & DOWNLOAD BAR */}
         <div className="w-full flex flex-col md:flex-row gap-6 mb-24">
            <div className="flex-1 bg-zinc-900 border border-white/5 rounded-[2.5rem] p-8 flex items-center justify-between shadow-xl">
               <div className="flex items-center gap-6">
                  <div className="w-14 h-14 bg-white/5 rounded-2xl flex items-center justify-center">
                     <Info className="w-6 h-6 text-zinc-400" />
                  </div>
                  <div className="text-left">
                     <p className="text-[10px] text-zinc-500 font-black uppercase tracking-widest mb-1">Captions Engine</p>
                     <p className="text-lg font-black uppercase italic tracking-tighter">
                        {subtitles.length} Cloud Sources Detected
                     </p>
                  </div>
               </div>
               <div className="flex gap-2">
                  <button 
                     onClick={() => setSubtitlesOn(!subtitlesOn)}
                     className={`px-6 py-4 rounded-2xl text-[10px] font-black uppercase tracking-widest border transition-all ${subtitlesOn ? 'bg-red-600 border-red-600 text-white' : 'bg-white/5 border-white/10 text-zinc-500'}`}
                  >
                     {subtitlesOn ? 'Captions On' : 'Captions Off'}
                  </button>
                  <button 
                     onClick={() => setShowSubModal(true)}
                     className="bg-white/5 hover:bg-white/10 px-8 py-4 rounded-2xl text-[10px] font-black uppercase tracking-widest border border-white/10 transition-all font-mono"
                  >
                     Source
                  </button>
               </div>
            </div>

            <button 
               onClick={downloadStream}
               className="bg-red-600 hover:bg-red-700 px-12 py-8 rounded-[2.5rem] flex flex-col items-center justify-center gap-2 group transition-all shadow-2xl shadow-red-600/20"
            >
               <Download className="w-8 h-8 group-hover:translate-y-1 transition-transform" />
               <span className="text-[10px] font-black uppercase tracking-[0.4em]">Offline Mirror</span>
            </button>
         </div>

         {/* SUBTITLE MODAL */}
         {showSubModal && (
            <div className="fixed inset-0 z-[100] flex items-center justify-center p-6">
               <div className="absolute inset-0 bg-black/90 backdrop-blur-3xl" onClick={() => setShowSubModal(false)} />
               <div className="relative w-full max-w-xl bg-zinc-900 border border-white/10 rounded-[4rem] p-12 space-y-10 animate-in fade-in zoom-in duration-500 shadow-[0_0_100px_rgba(0,0,0,1)]">
                  <div className="text-center space-y-3">
                     <div className="w-20 h-20 bg-red-600/10 rounded-[2rem] flex items-center justify-center mx-auto border border-red-600/20">
                        <Monitor className="w-10 h-10 text-red-600" />
                     </div>
                     <h2 className="text-4xl font-black uppercase italic tracking-tighter">Captions Hub</h2>
                     <p className="text-[10px] text-zinc-500 font-black uppercase tracking-[0.3em]">Official Database & OpenSubtitles</p>
                  </div>
                  
                  <div className="grid grid-cols-2 gap-4 max-h-[40vh] overflow-y-auto pr-2 custom-scrollbar">
                     <button 
                        onClick={() => { setSubtitlesOn(false); setShowSubModal(false); }}
                        className={`p-6 rounded-3xl flex flex-col gap-2 group transition-all border ${!subtitlesOn ? 'bg-red-600 border-red-600 shadow-xl' : 'bg-white/5 border-white/5 hover:bg-white/10'}`}
                     >
                        <span className="font-black uppercase tracking-widest text-xs italic text-white/50">Disabled</span>
                        <span className="text-[9px] font-bold text-white/30 uppercase leading-none">Hide All Captions</span>
                     </button>
                     
                     <button 
                        onClick={() => { setSelectedSubId(null); setSubtitlesOn(true); setShowSubModal(false); }}
                        className={`p-6 rounded-3xl flex flex-col gap-2 group transition-all border ${subtitlesOn && selectedSubId === null ? 'bg-zinc-800 border-zinc-700 shadow-xl' : 'bg-white/5 border-white/5 hover:bg-white/10'}`}
                     >
                        <span className="font-black uppercase tracking-widest text-xs italic">Auto Detect</span>
                        <span className="text-[9px] font-bold text-white/50 uppercase leading-none">Smart Language Priority</span>
                     </button>
                     
                     {subtitles.map((sub) => (
                        <button 
                           key={sub.sid}
                           onClick={() => { setSelectedSubId(sub.sid); setSubtitlesOn(true); setShowSubModal(false); }}
                           className={`p-6 rounded-3xl flex flex-col gap-2 group transition-all border ${selectedSubId === sub.sid ? 'bg-red-600 border-red-600 shadow-xl' : 'bg-white/5 border-white/5 hover:bg-white/10'}`}
                        >
                           <span className="font-black uppercase tracking-widest text-xs italic">{sub.language}</span>
                           <span className="text-[9px] font-bold text-white/50 uppercase leading-none">Cloud Source: {sub.sid.slice(0, 8)}</span>
                        </button>
                     ))}
                  </div>

                  <div className="bg-white/5 p-6 rounded-3xl border border-white/10">
                    <p className="text-[10px] text-zinc-500 font-bold uppercase tracking-tight leading-relaxed">
                        Note: External subtitles are injected via the web renderer. If using an external player (VLC/MPV), subtitles are passed as separate file arguments.
                    </p>
                  </div>
                  
                  <button 
                     onClick={() => setShowSubModal(false)}
                     className="w-full py-6 bg-zinc-800 rounded-3xl text-[10px] font-black uppercase tracking-[0.3em] hover:bg-zinc-700 transition-colors"
                  >
                     Dismiss
                  </button>
               </div>
            </div>
         )}
         {showLoginModal && (
            <div className="fixed inset-0 z-[100] flex items-center justify-center p-6 animate-in fade-in duration-300">
               <div className="absolute inset-0 bg-black/90 backdrop-blur-3xl" onClick={() => setShowLoginModal(false)} />
               <div className="relative w-full max-w-md bg-zinc-950 border border-white/10 rounded-[3.5rem] p-12 overflow-hidden shadow-2xl flex flex-col text-white">
                  <div className="text-center mb-10 space-y-2">
                     <div className="w-16 h-16 bg-yellow-500/10 rounded-3xl flex items-center justify-center mx-auto border border-yellow-500/20">
                        <UserPlus className="w-8 h-8 text-yellow-500" />
                     </div>
                     <h2 className="text-4xl font-black uppercase italic tracking-tighter mt-4">
                        {authMode === 'login' ? 'Welcome Back' : 'Join the Club'}
                     </h2>
                     <p className="text-white/40 text-[10px] font-bold uppercase tracking-[0.3em] mt-2">Personalize Your Cinematic Experience</p>
                  </div>

                  <form onSubmit={handleAuth} className="space-y-4">
                     <div className="space-y-1.5">
                        <label className="text-[9px] font-black uppercase tracking-widest text-white/30 ml-4">Account ID</label>
                        <div className="relative group">
                           <User className="absolute left-5 top-1/2 -translate-y-1/2 w-4 h-4 text-white/20 group-focus-within:text-yellow-500 transition-colors" />
                           <input 
                            type="text" 
                            placeholder="Email or Phone Number"
                            className="w-full bg-white/5 border border-white/10 rounded-2xl py-4 pl-14 pr-6 text-sm font-bold focus:border-yellow-500/50 focus:bg-white/10 transition-all outline-none"
                            value={loginForm.account}
                            onChange={(e) => setLoginForm({ ...loginForm, account: e.target.value })}
                           />
                        </div>
                     </div>

                     <div className="space-y-1.5">
                        <label className="text-[9px] font-black uppercase tracking-widest text-white/30 ml-4">
                          {authMode === 'login' ? 'Access Key' : 'Create Password'}
                        </label>
                        <div className="relative group">
                           <Zap className="absolute left-5 top-1/2 -translate-y-1/2 w-4 h-4 text-white/20 group-focus-within:text-yellow-500 transition-colors" />
                           <input 
                            type="password" 
                            placeholder={authMode === 'login' ? "••••••••••••" : "Choose a secure password"}
                            className="w-full bg-white/5 border border-white/10 rounded-2xl py-4 pl-14 pr-6 text-sm font-bold focus:border-yellow-500/50 focus:bg-white/10 transition-all outline-none"
                            value={loginForm.password}
                            onChange={(e) => setLoginForm({ ...loginForm, password: e.target.value })}
                           />
                        </div>
                     </div>

                     {authMode === 'register' && (
                       <div className="space-y-3 pt-2">
                           <div className="flex items-center gap-2">
                              <div className="h-px flex-1 bg-white/5" />
                              <span className="text-[8px] font-black uppercase tracking-widest text-white/10">Verification Zone</span>
                              <div className="h-px flex-1 bg-white/5" />
                           </div>
                           <div className="flex gap-2">
                             <div className="relative flex-1 group">
                                <Send className="absolute left-5 top-1/2 -translate-y-1/2 w-4 h-4 text-white/20" />
                                <input 
                                  type="text" 
                                  placeholder="6-Digit OTP"
                                  className="w-full bg-white/5 border border-white/10 rounded-2xl py-4 pl-14 pr-6 text-sm font-bold outline-none"
                                  value={loginForm.otp}
                                  onChange={(e) => setLoginForm({ ...loginForm, otp: e.target.value })}
                                />
                             </div>
                             <button 
                               type="button"
                               onClick={handleRequestOtp}
                               className="px-6 bg-white/5 border border-white/10 rounded-2xl font-black text-[9px] uppercase tracking-widest hover:bg-yellow-500 hover:text-black transition-all disabled:opacity-50"
                               disabled={otpLoading || otpSent}
                             >
                                {otpLoading ? '...' : otpSent ? 'SENT' : 'SEND'}
                             </button>
                           </div>
                       </div>
                     )}

                     <button 
                      type="submit" 
                      className="w-full bg-yellow-500 text-black py-5 rounded-2xl font-black text-xs uppercase tracking-[0.2em] shadow-xl shadow-yellow-500/20 hover:scale-[1.02] active:scale-[0.98] transition-all mt-6 disabled:opacity-50"
                      disabled={loginLoading}
                     >
                       {loginLoading ? 'Synchronizing...' : authMode === 'login' ? 'Authenticate' : 'Create Account'}
                     </button>
                  </form>

                  <div className="mt-8 pt-8 border-t border-white/5 text-center">
                     <button 
                      onClick={() => { setAuthMode(authMode === 'login' ? 'register' : 'login'); setOtpSent(false); }}
                      className="text-[10px] font-black uppercase tracking-widest text-white/20 hover:text-yellow-500 transition-colors"
                     >
                       {authMode === 'login' ? "Don't have an access key? Join now" : "Already a member? Secure Login"}
                     </button>
                  </div>
               </div>
            </div>
         )}
      </div>
    </div>
  );
}
