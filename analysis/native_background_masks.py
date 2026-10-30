"""Owned 1.15.0 background alpha masks; preserves 1.14.5 live OEM rendering.

The native 960x360 dimensions remain unchanged. Data is generated solely from
our reviewed curve/alpha design, not from OEM background RGB. The old HMI boot,
clear-color, premultiplied window and receiver/wire contracts are retained.
"""
import base64
import hashlib
import io
import zlib

from PIL import Image

from analyze_kzb_hmi import KzbDirectory
from navigation_layout import repack, words, digest
from native_background_alpha import (build_alpha_hmi, build_plain_scene,
    build_runtime_receiver, build_runtime_kzb as previous_kzb)

BASE_KZB_SHA = "cd0be8dd99c9cb384419edaa7a8cf4f0e7778069d742edb8df6d09d2eb0ad454"
MASKS = {
    "Full": (
        "c-rmVX;&0!p0M%p=sEofrfYKnwFCte5l|FRK^9pn0h3)pSxjf9%TlW>=A3D}hUQqd3)4OGq9@ChWD$i#0og%8kVQ5Zc9b10wP((J"
        "fsBdB#I4IJCHt8tBQvkxOW#IZ{_p$Fh(Hhk00000000000000000000000000000000000000000000000000000000000000000"
        "00000000000000000000000000000000000WKIYCEc%dprq7?w&P-2DO};V#tM&RWn3|fNnSJ`4uw@eMTbUXz*7*3?<442&5AXN("
        "ba!`kbpms`y1Ki2dhb8%AAa<BY<&F9xPKZpo&8^BD@Hp#H90Xp_GoxO%CzTIrgudh?QN}hnj3G{*Va^5RaHtVVD<DjLRD3DO>O<n"
        "#^yV%ZS5V>-SR5z9x3dB;YVZR6O&WZZ>CIn|8Kf|{&Z$qtoG4}6m54`XGeQ$bJMMcx|-_hs+W=8xN)Prtn~UdZPAs>`FXh-O-|0G"
        "ITvB2UfuyYIT}rFUjF4PMcQlEOUuf|o8@KNRn^rsbq%+gnp@jDI=i~1lt&(kB~Q=14w({-EBD3InW@R~$6~hkdb;j*w6;jIwYsvR"
        "yzF|3m}$YQOvOue;lhRU=g$71{<9`qa_02uQ>RXy1Wt-K!s*jz^tXlP&*~q}o;xqzEQY!bVHb#Dms~F^uc)k+W^_wy$K9@;dt%6s"
        "$0w&|p1z>s`Ih_q$@Juy)NOZXM_WsyIBKgZ%B5%v@-#UYFP!_~Ki|kyyhMNa!yitZ`2M)`Pgw^g-+p`a=r^LTVdbQ|;ONnBzt!Is"
        "S<)SH{QDCp#2ZFm_N%b}^M`X6F6L<R3Z#_FE2_ky+}P6A(b+9^JT^J~<T(|8V7U`xBmMV#I@|9w-K?vwEH5o7DlEv&xp@ApA=*<X"
        "PaJ<EQ$wUjjvPLG=#cbJ>8kX!lzsd5CMPH9?SU0D+ylwUd-v^2NfU1k=?*z`=<wkqM+|Ad5%%#DCr=qtK70ORPHsVAQAufeWp&-n"
        "raSGOJ@@-Z#wMuv7t(#9pSk*STN>+YD$B1GUn$7Fbit7A>672jMSJw?!(yoE2ll6>rp#q3UZ~x>6B85S<6@$BMMiuP78()~92^`3"
        "h{RhVBqTKKi-^cw(J^uH35kijcN^N4!cIv|+kZd|`_SR9kItq1{mIjYkS|=yEx1yAt-P|PzOiMlVsRF~AmX>$eK9*VK05GVuH1^U"
        "lA_Cbm(Kqnb$jZBAzQIp{cKI$m%L|pLR^e8O3E}e#8BzZojU^q0|Na0e0{cgZSmN&(cRrm<O;Y-cY?e7#!VhuyteuH`uPV01O|#X"
        "jG^q1P$}#vWlUVc?mfx-QuQ<1P_h{E327p0emH+A?{ZN|S;bt%4+ciZr)FPReZ%_y#h%Sfj1JuI?zq#`P*W+Dn|tY;TB8qF>h_x>"
        "QnqO+d-o*9i_t2=LxY0?#ZI?*zsgj)MjJM4aB*>2@4VJ&jor%Swl+4)^p?Wv8SVrd8{6e8?bbN0bzU#tDB|rhSGFN+?`>k&fkDBc"
        ";R-S3_{2SXQ_`f6k9?!=Sjt$VK6fcss<^VIq3KRX_x*v<iJ50sJBamr-Px(Jk-px})~1H)8`p~q#hELWE3TBqa=$q&u94GIl6S|)"
        "DkBwPAwhwLXg9jKik&(+*nhs_Wv2Sev_!P{vrj+%`-KaDcUt)OAAkDUV(CtKUA7qZiqGvGoW!nO-8LFh4h#wjQ$#9b<98>gh&SQE"
        "!{101e}7V{Se(U$#n*3CH#D_&_V$g8P0c>HwubfX8pdvW%dOgq(&ED0i)W>oYbf{7!SwxNxN*@@im>27e_wG)yvg0w#d)oR{mSJw"
        "%a)3re)8*|&GheppYgL{|N0ZL>!r(VmanvTSnKTK>b^-_Z2S5L28Su4qT|Gj_op8`WT;r0#b+<(78aLQ)ZS`oHw<E{Y4~Nk{rAMN"
        "TVGvXay9>wl<rBX+@pu}<tD}|Bf^6N{kD5=+2poi{aOdRm9{oZm%JS<HzWQp!j@8Aved?QrJcjt^&8waZSmgj7Z@BKp^Qz`SA6)W"
        "RIxOQ&tA&ET2fwJFAm~+{Z`Si{-{cvyZ1WpG}cv?7GKs}kQU5Rx?hXC+|=a6IDNT0wt0EDZ&>GOztVQu(#3CN%Z-NrUnz?rFJ8LL"
        "cBQ@Jx()6gUfXu)D~?M{P8D~?UmJc))~GLNE*F<p)-~SgyeH0K{h^lS#`|pg@!)-N?$%XaFDl46C#GvSq)Jz%Bqc;ghKCr+by??N"
        "x58%0r?0x@rh>^rUij-zm)NYZb6DqMs5m4%GCCnCMgLoJmUshaYtH2q6kV^Z6X)>#!N=3jEHPmz-l<3Zy&cUBRi(uRIcMj_?vaD("
        "X~_vOk>Nr9KHi)Ex?Jud$fV;pD&Fkv;~x|r8IzElmM-4FhCzHbr=YmBs-d}~xBt<Ug(a+yceKB!t+Dn-NulQ4T)Wa<cTe0dMM!{;"
        "w};z$M|;YJIaF-#xZcgf+b1AIu`6zmv?rcx_?)J&<VI~{TTlO}<s@u~x39acv8Jpz|Kh85)AuIEM27A3+qT8s#mRofvd=!ET$o42"
        "AAPoLg}sxD`<88fJHsMl68EORYWQM)aam1cTX&yDBy3nV8{#d>JFj`wZbEcKXrQmRhpV&0D%-_|bh$fVJ{2$gXtC`o2WM9gZ{NVs"
        "i0Fh@4QtNl6&VsX_l2}h_k8-1v}}Ge-UBJSV<JKWd^|UbV|V$o&-4psZV8xS#0!7@*|O#0Al~Td6A&5^vpeO$+X?sdKbn4SrUi@t"
        "^E@3N>}_v)Bi{7%q`0WCKwqzoE{=A~mwe3Fh4~NSkC!aBb9CA0<r^3l6_+H9;a3Sawf7E=KQ+T4EREjLhn>xJ<;D3g<4xNW6A|pU"
        ")nkK`*zQM+U0BW_{zz=tX@kdBzu<_NJ!vl!&Mz*nYwmnFYHmYV-1SZj_O{)uEGfA7I$lMPuc!OE)hi5hm+KD8YWU+ND^{;__w)@?"
        "yiWLHK}qG!w%);sXXaC|PWR(je^+x|*;UQk@w%<Gv-yN}VYv-|Vq>?~?d^m$SIg>}yZXm|G>?L%diy$?s;?DX&`9xqnVvKk?<#$~"
        "TxVEv!(zg#<`Pay|58d=bD`i`byH`bITWm~_ikgAHcvl#zfvW~Mi4KoGvSEXWYt&tA)KeJYP>7^g2j5r#d<4?HTuz;o|+I9vV(YG"
        "tqJc4iAqS-ucI}Z;!3gLaXA;Pdp^<M*)&&gx*|CyJiyz1ogMMQIuo{A=k6U49+Rv{pDVbjvwz~bPOigucBuPyl~!NxSLwTVh4^k>"
        "zuNXw;)S&){Hg8g^_zV|cI{68N?)+H>UQ_gtSm>c?%CLb*4k@%`jvB9LPX$J_qCrdCSF)~!izs&>%KKGA|Xw`iq5-M+xlSanNEJg"
        "cV?*bR{3Q^z43|wuMKN##dR~65LTY>!cT11Z14(D#2X5}Tz;!_XhvouSodQ5!JX=2{mNOa*Wc5{ekr3DFbv_P_AZ|OV!`@VbaC~a"
        "2jef~6tM2ck?vb%`5NieDlsC!bG_YSMlbMs2rsr<?->x0D4k?!^2=^@kNhZ~fOSs>JL<IJ@Kq`HL<M@eP%rS)f?d1<qxL9N;t1B("
        "bqqd{OTfC>{?@8OY51l@2YYXzUf|sYZ}1L|PLW1%VO4AYtWGBR_O;y558uRaAGg&@s26y5!An-V`GhCxNAQi7K3N2;n|;_^CJo=z"
        "=$%`fY^WFb`GRenw(N{fl}2z`^FtW~EKc9sr5bVgCMkSeKVL+>z%LcN=yO*eF&0&pru4SyC$R2mf3uWtczm#@)6#`pAK;fQT<YW*"
        "93L(P+}!`v!~wq;x>F|Q8x!De$M6L{WCYu}2gFDLm)#k9VcPLK)=?qltMpxO%kTw0Yy{h`_f<*(S9FY-ascaQdTXV8cWqzG@C816"
        "1h3t`OA5HQcgBPPzZhuJO4E0{<07sJ@ZlCYZkHyowrRk0D`?%s-Ey_K_w{umUtoR#JNb%xV0HQ336l-r7yS*IOl6|~I`ReP8}K^+"
        "L}jL?q2FW!c>1omdiD)=Azxtr0lNhIiYw^5)23QJk2H#_=crBO3m6mdrYLa*-8f>R1@w#im08Lp-(PVd0Au{hH%XaQdEZ0}=-Ip4"
        "3}3~1emDY*vS7W!H$!`O)-?T2wWw9@KCGSrV^`2V?kaW5lxg}MznQI!*}z2rjI|*~nSFEIH2prV%~pglegWeLaF`;y_OWUD9j*G0"
        "F9X>90><xv1HSx^s!`MQJ6!R{1HSYN7~ik&fj?FZo2K8P^6dTF=oc`)-);M|%ZE(VZ`tXzt@H~R-|yD6(`6>=cd+bKsyF=t#`o);"
        "da7*DME#bYO!cB)!1#W>QcsqetY79Y@VCxill5D6Dvf@Dzop+aldYeJ%1)<q{tWyL{ccM)+4<E_dA7=jegWhA^--DZ_oLy8KmLOs"
        "&rbqITjci-CVPHkr0U;)vvVca05I0doxl0_su9!l`=~~(3bo@J0LHQlRjF$pnWo>ddTquRYq$o0vDSQ%p{*Y?(dX-$8nSjdbASaH"
        "X@T>utcE5NeZWrFo_j3LjeY@R`*n*umfLQs4?y=8W-C1C7cjP84@Guiuc^NO{IED%;Yq)MvHf}~vWp*@s^5X@*$Q4D1dQ%?t0MdQ"
        "fT{W&DnFyz!2=wCkrwSxoiWwpSAsB7`KN#40S>@eD|i0WpDIU8)vx{m4tuTwU>tiBKE5FcV-4Dj2uH30U>wJY3~j@hsrsG1ed%9f"
        "HgJFi7-hkRn18u++f;vlP|)2i$lT2jz<`ncdhE_DxNEY%-!0rPK9RDG18Be~i?*elD86s9e1(D1GY9-va2)_+tnfc@rgXq`{f<<r"
        "RUy1V2pGjKM5V48F<rmX8-yG{1ICe#pX<j=*RON{9qY;g7GQ)0uCb;(ewGfPFaJKtlLIWk2#Y+Eet+47$IpWBKzlOX*On^*%%81q"
        "`bq5r6Xq)n-B7E7R&gbO`CApFQr{RdWxtQ>v>6HxumB@CDl)Wnk4@RH^a-pO4zPgvTi_aFx{qJIdV=!zNnRXa0rR%VE9v(ICOy6q"
        "goh<3_xmm9S^)F4+;9KMl7}YER~W8Pt3r4M7??Nv5S7|ge>>&PC$OTNxfZ~DIY*iH=_|t%SUJZMJlFsO^WFY>Bpl1RJ#E^4bzN7E"
        "r~24%HGuiC@ku>?rOUL(SAx)As#XQ@3KB3+c0nq2X}^i{6&}@SGa}Y;HGuh97m=Yg-M8yrnrgcE+jus>z<jsA@xQ&;G-c|3b)ALB"
        "Q`i6l^WFZY953uN_4Zc~`q%&i#%+K5OrEbW$_5xPZu>iG`hKUH8nV~`1M}VfW;K|8`>WpopH{K~2IjZ@Ri2i^_LmJXV7}X5Ho$=S"
        "Zhxf>@Fji$2Ijf_{p}?=Y=5N<@D+Xm2IjZ@O*wu=7TaGoz<~K~f5i=Otu`Z)cYuKp<{X)!tsRv|zf-q!ewVnJ4KVPb+uzNJzstEj"
        "C69h}T~|+}`|)Q8fe*C8Fa5;TE_rN!1);zEPhW-e6=>iCIfj4rr}BQ8<SRU`Kl{(I8(BaDAGUnn82is>>mSRd-|0JfSxO)N3=;4`"
        "Y<-kjd3UDe(yy+!M6C*0!*u~Z#F`M5x};Yw+h0K#uGVHK-B>^aAF_OQQ)XzZhh>wmFm+q=UDDQNTp8f~mTgV?P9v9JkMs-Z?qann"
        "kiR1ee1KJfDs^$Ue3s9GFj&buNWh1Y-aV=ulu^FIWMe}Xe})10knOKWLRLfLq@4QIbzb>CZ3lmc5O}ZUJJP<F%ezOf9>FS8t9S$p"
        "ct6K*mAb57R{08#>a`iMn^-^t?^`}^ip|j0Kay9!(|7W-ls+t=f%h$+eUw@G@_G2`<s(?v)T&Sit`6|a970v<YrXQ?{tCiyjW#2O"
        "cNl>8ET1>VWN2%KWtOin)tt*a48Z$H?=IvvPsy!cU5}OpH1HwIXKjz%md}QF7+639?_WMw%WnD10va&i<+HSaE@1%;ynp#zBE#kL"
        "+ya_MIDlVRK5vrg@_B9ny<;WU2>3ZGcgS@4Jhy-jcV56n0^V(bbGTfW&xS`h;yr)GRRZ4aSDx{5e0XE-8R(Y$EM<T_R|$AG`v7HD"
        "e#^8x`_<jMu2x05a)JfC`}E2+Ql-9rPoB$XK^Un!m#N%p!*v4Q#b&EA^IYACO!F0HS_{>xAie_)ywjQ>mAbHXMy~zp9+Yb{cDb{F"
        "2Hv%NcHfnuEq@@_<+C7+-@5c&@^+q~1m3}Rd-8XeZjH+}UqRPZtX75a3?=Xm4k0RaahH6@uOJLn@e&g7?&Y)e>QU8@jPo@-L+Q&i"
        "l)z7{^p)+ilRQHS{4!ZTJIOPYz%P>Zvy*RqM>cRZR||OC>OdKOH}<E`P?oAyQT!=j;IB@v+@e(K(tGk=J`2KV{rO`_+xd_?@RsG<"
        "la8IQAC-B&!fe|WwJK~KCs@Fnr&sI3RO%~jvvTiO_pm~n5x0dCEa1)4t1WRE+KPvAUp@=ML{lzr0RwN7-u%sNnvi|Ig0AP9S{2C&"
        "7VzfjRisLNtw;XjckV4<POyMCPp@SC=I>i?0k;*ZRiSG+!2({MUabvPsSDd?%%NZ1!yDR+SWd8j*QZyp8QL2U&0+a0onUD=!2;es"
        "z0#P&=@lnffEk}&ae@Vy@9EX+6D*#=0`#X>>&)l$if6EZzdpS(lV`7Z1`BxS=~b?|oL;>;!Qw4K;P2+%95kQPtGN@bV@caraP5E<"
        "+mnu&(dpIew+Q(T7O=qCJl-7q$q81x7bjTY^vWyVyiTw5Cs?=hvXnuN3}9IM_;n0YX64<UFspn8UGH_ZD%yhq3~L|19?>fG^<MKD"
        "ztVTGw3#XXc3eMLH#`57Os)LCd-anOtoEWaU-8GhVdWONMt*gssD0Ml`qlMU{lG)euy)d;&p%Z4o7?hP5GGqL^Ai%R{q#!u@#u2P"
        "q}k;w=pNkAW+ZrXf(2_nz4A`T(B61pe&bgV#-*Q-f}I$^u-@_O6fFICG;W6Z3cB7>wJMgsWd<uAzn-xwb!o3Tj$c6-ZO~@&3<s=u"
        "{5l3HGqnw)W|^-r+o4sfqInG%Ry%&TM61-=j#=~US2s|5{$EpfIxv7?o#WSGXX?M6uN^SY<+C76wH0L_j^4}whINkL&C!Rmi`u5l"
        "G+#m2Uw!Volt2bBtaJPZrhIp<y5C&KuQ-5PuVx=oGJs*7<5zho`)aG%j$d&A_f?<!9RnEFI(~n5uDZ{B$FCqvwq8BM0EV@W-!oTR"
        "C(Srt1~3@E0s|Oe{4#(6#xDaHu!iw#7{K491o9L#tZMu^>VNw+`|+zEz&wNns~f-3hqEnU{LT&F)SbKs3@aMHPCHXAWBf`FVHN$`"
        "kr=+^4l5eJUNJ}ht;n1oy?XO0=$iAH%8>PZiV{|0(fSZ&=J}d_OBlb>Ye>avRl;^VR?x7H)wA991eLnD%_7FH{ykuArc&X~3L4h2"
        "dUjVRGqvXX-tS+V?kG{KlKgmx16DD99sH71>XMFW3&~f|4K`@AzTCx^bYK<ZcgwCXv$PF^mNI??VWzY6&&N`OIl_W999{*d9{Y1?"
        "=ZwYVE9izB3)HH3AA45Nu=Lfly-&PKUC=meIpbFlp7d5~GnEniJyBTv>e(YgnW?SpePTiR3c9g7#cEY}kP9nlSoZ4KB`92_F1|Bn"
        "N#j>~h@)PcrHc1;Bw$!_zK*`}sw{1Nzh&hs9buK9%~VEttzrud%ia90@`_Sso-Mbe!>hRgJaW58TtSC#Vhaq*-TZC}7gx_kw?`~("
        "{0hR;`?cZ<I*BbXEP3;rB(9!o??1J;d<EUa-5Z*$Lka%t2pE=`?>hg4Ls^;|cPA`w{7Nh6mJ)TQGIj?6!!q;T5v$Bpm$ZynV!nd#"
        "tnb!UaSI%?!+8;hSg^FCt3}Q`V#Lkw)mwefEHYm~H{Dw&Zh^yN{asfq<buPJEL`F09~&-ie(QRtEpz-zTi~uLjTmr3@D_(f3z)&M"
        "l-avrk;9hY1TkMtRo8^2<|_vLsJ%i8I7#8_wtCq@5{4zjyKvcRH(y1Ply61*Bg@TK5S|aUN&zd<<3fElI(@!GOqhsazUhkbF8SPP"
        "qfcmDx<bmgb?CVT=PT%b9Bi%BWF1To-xm|+=jrBTw|wa*`iKE@v+$Fp%k7-pJpICA_JyY(%+gf04*qD#<5vv$$zWTJCR-fAze!Dq"
        "4EEdV;pXgMw{p3yt<5rnrGRYB-J~|Qw#!%AIXJs{Z1oF{Oi2BWIDE4;HEn}WEIVH@;HN`(>#vC;_@KDp*qfmIA~?WjtLNrT8{OUA"
        "-9)Z{j7j&Xc&l#QwApj3PeAY&%7ne*cJQD$e6Q8t9eQf%`HBHQ8|`kY&}3)p6aL5ay@|2PNJV&9Xh=wKaBvVHYvN5B5)v8~u835|"
        "ChkrDM}556*_w)`?$KwKpRXA3^RWl*4dqu23F{+1us<zj-`>5+$w_*902wmeoyp02_wGwc+kZfxt|8v5<qho*#-3Y4zGA@tJw4pp"
        "+E7t)Sxk5?V$rupkA5Ti8jvmN4n2DG+qqjdOS*qGmrE)dT6>45|Jyq96$92ipBTK?-c(m{?P`9GK4Q_C)2C0JI&~6|G4U2XefrGY"
        "Evw1Nzk02ruBrXr;KXyCwd5-m{6Ej8M*Di&n{U=sl$I1-xtyPutI5g9xise@ATuxTP4PC><mTmHzEV_DT2XVexxJ@vbn4mvSW&(D"
        "5v=>m)5+0+``sO_w;ONP*Va^5RaHtV0NK*tqE%JZHMR9O8*jIEbl)EsoqYNiozA-QolE$?p3P1^9v<j>aId$gySuBa6Og^GuI}!h"
        "-g^)F28JI`&OZAuYmE0*!r~l$_GETudU|SV@|6idc3$6~Q&ZE^GqX>g{Y8HR3)W2sm?;4O000000000000000000000000000000"
        "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        "00000005Ym{{=H9_df"
    ),
    "Center": (
        "c-rmT$xoD7qsQ^v>8()A0)l`bf*^>rOadi{ARSjS7en{TwSXP=Zkr)2T-$(50=h71paemn7(|9*lCeN2W{N@VXf%D_d#Xxp(dAQl"
        "%zOL$N&N-CoNvxaCC`8U0{{R3000000000000000000000000000000000000000000000000000000000000000000000000000"
        "000000000000000000000000000000001oKe<2%A8!YPY5AWU_9=`tU;NW0?e}8XpZ+CasVA$E&-rm~U+}zmMSYKaTTU%Y#>sMBm"
        "mtR3xUS3(z>sMFBFzf4LqRq{%t?lid9fLuPCngj_iaGz;_VDn{yAOYJ=3ud__x+(^L%;IHnOs_0T3poW78d5`=4NMSW~QfKPEAct"
        "PK=L_jcK)`qa!23LqjiMGBh+iGBP@<)sBsgk55caPEEa>o}QVRot>MTUs%xT78k{wUyQq=-!L4$=Msj6F5kBX{rtr6;PWpH4D|Q+"
        "_4W2@G(A1t-CbSJIy*Z$p0>BQwLMX*TU%RNnwy*dgdm1!X=!a$tDm&BwYNX*=;-Wx*45SB-P5Dd^!E1k_4f}99GP}-cw$~}c*_+G"
        "i{$%eOQ+SSA3u8drKYB)#>U2mh6h4@eO+B$ZEa0WO?7qkeW9wdva+J0;tvR72r);MaThU<7^${a%vE1+T&|%(j3}l&GVP<sYK>O6"
        "^@f09fyeXPt+_#U!@axZW#1_MsN|Dkv)}QJkJEgf>eF`Lu3CQgUW0mYZtE>iXh`<npc`ziD!)^df9t4xvjXumEBx%X$!{<*ukp`1"
        "ALlYnXY%#1MR&@png?};_nbaReLWxQwd$(kqMNFe8%L$2q@<>%rlqB&r)OkjWM*b&Wo4;U+1c4SIk~yHd3kxiz(h<T2Fb}0)2LKp"
        "Br%m3EInOJCk7NVem$(}W>ImKTB|?g1r3Ql80IvM<wd_<SB76aA}lOCJR&kODk|zybaYHiY^+jwIW8_f{z^i^)x^Z4q-)8^KSM}P"
        "zLu1fn0PfI;YxgbT-;@)GB!3QCOZ0(m@YChB0M}S?C7%L%Im)tl{aeU3?DdukmlXSg!<l{o7W@!JWn3s;o<4&<#o#2+sEg$ukRT@"
        "KY#zT0Re%5=YoQQgU^SATnG*Q2|{S-g^-Z*!NEa6=K=!*0?zvT`}v*m^*!z5<L!OQ%gfW#!{g|(o_-P6Z{E45p4fQD`Gd57ul~{x"
        "KfYU}Qu;e9td1RFWo>P(P}tbm+S=LK+dDWoIyyNWKYrZV*~P`x^@N+7yZcE9?(S}GCtO`!T%4W7JWfuIjt&m?_I7r*wl+2jg&6Vc"
        "T@}v$N>$O_$HV%+_y-Hp`aN78d{myF67H$^@dwD_M}=p2N`CpH!R15xg~WbeJ%3ns>&C^CRzLg?WbuR5$%{8`l|6j^ihd!n-`Ah|"
        "Jx0HB^?U47zpv>R^h>{>U;3qA&@bp0^h>`0{n9V!7xWAI1^t44=@+11`o({9zXwb7`@Q;II-p<BFa6@*+wVXV{eF*rn+E6?Qv2Os"
        "H1!+iLBI0#>k($^cX6M7A+_KBrn3B$Fb}>Tl&xPYkFb>dvZj9eh2(y9{f%YD@As|gSFV1ojo<H=HTLW17ZUs3)AiBs_vyD!w@1H_"
        "*zZDLV`+YBgctqF*RNMZYJO>B-va$Ya=*O|qTfg_1^vp^ufi)*^xM!&zmVGR?!39*Q}ioezo*Ro&hOGMB=$R}c~F|47UgY2zjF0!"
        ";~kZjU;02ZN57z7`o+JtU&CyVsb3#k`jx9+TOU)uJ+lV-g(dn;zeK-s_IoLveqo7zcV@clOAFGYPutP2T>aXej!rKqt?!=Mp<hVv"
        "x1=B=+LwOi>(@6rqo9O-A-&)2>8?7_Z;Y=!{mRv^y>E=@x2|h?n|@)Le$UXaeEpuGUs$H!t(VV4znQUq4)iNmzYcz}nWEokFSqCy"
        "68oL%tfk-Y(Qj?%6#YVCznkWMmHv+OD_6gc{z`MdoAe8b{Z4k&loV#sue|+c6_(U=OwunT_Pb&3_wrdM`jx9+r?Z#M{cg}NB=$S;"
        ")Yxxa0R76>Z$O-}-=`Dw3#t8%w^tVzs^S8U)303p9uJ696&6>wkJB$C_PcKGH$ISl<?A;v-rVmx{X$~DV{M|}Z2FbA-)zxu+Zg>q"
        "V!vx+ZTCgLSI#-puU!2)pSvRZz27#rM!%5Qul9+t--ICgm9O8R1Y^HXwDb$f{i;R3ISD~7^eb1tE<p)7qF*)rLTbOOqo#hZ2Gg&6"
        "{RUq(^*g#szmVE*Yt`?!a}$GI=~u3PU4s*IZ~tD^O23fWuYRPp(%A2L`jxNW^TvKFTSxTt3#t9K82e2MIYGa2^?M>D$=GiT{X%NL"
        "E5pWq^O8c`=vS_O-9nP`jQtL;&@UwRJJehu`n`66e&y@;!Zp!vMe`8-LSnzm=6;hy-RW1Ze%(Wp&HXOZFC_N+LiGFFFZ3&KzrXw@"
        "`h7vakl6357k~aC`u+K*lk_WBzbAkCS@iqIpD$j~FX)$kLBI4%zo1{xFX)$k0s5t1&@bp0^b7h0{n9T$zw`_G1^t44=@+11`UU-h"
        "enG#WU(hf80`yD2pkL50=$C#0`lVmcFX$Ka3;G59(l0>2^b7h0{eph!mwrLN^b7i>U-||8f__22^b620{epf$zo1{xFX)$k0s5t1"
        "&@bp0^h>`0{n9V!7xWAI1^t44=@+11`UU-henG$VOTVCB`UU;cFa3gkLBF71`UU8henG#WU(he;7xYWN0R7T0=oj=0`lVlhe(4wV"
        "3;G59f__22^b620{epf$zo1|G1?ZQ4LBF71&@bp0^h>`0{n9V!7xWAIrC<65{n9V!mwxFN^b7h0{n9T$zw`_G1^t44LBF71`UU8h"
        "enG#WU(hf80`yD2pkL50=oj=0`lVlhe(4wV3;G59(l7mje(4wVOTY9B`UU-he(4vWU-||8f__22pkG+7-{lw06{6qdP<Q&3t6%rf"
        "WYKR$^NVHrg~Wb`M8CK5=vUr;^KOfNhv*j)`&}{jd+mZ7{mRv^+l6c9epl!h68jx)sTBPth0w2j{e~ooek)ss=@%0F)sGna%}ok9"
        "LBDeKdm<z$*Vylfo_-;<-&SM4iRWGESFV0t&nFuDZKYpG?RRywwaVCUF#XEcZ?Li7s@Bm}`i0bf)%S}FbFK!v(63zmx&&X%DJ;IP"
        "re8?ycTH>RHzA0A<?A;n!PKvIjea4u-zTEq?1Ug^`jx9+=b(ga(eD%bh2(z6+Kl~PIY+<p^?UA$vEQ~a`h~=P*T>qbMZfWZ$LUwD"
        "evb#ni+-!y#@6W<68jx*7yYW}SKfYAqTlv$`h~=PH_ZLU1<<d2{RYID``w^lNbGmwX-!FCR$PD+{mRv^Q$So+VM)!?3HpW9ekVJO"
        "{a!vxzw-5a_Oh|xj!F83#C|u;{VM$(=~u3P9sQN&emChC68oL%tSu?Xq+faa%`7OX?VO@tNbGmZ+;6O(1O3X?uY+H#x!*1Ng~Wbe"
        "KC7eO@6m7FvzPP>iT!R*cZq&8V$RspuU!4wpNYv3{dP@n(=RO3uP^<|*RL=A!ZQ8t%yid_exrTu=vS_O?R=v}zxCZSJM;_b{gxJ_"
        "N1vu&`T9K_onBB%zmVRqVYcT%X@2@8A6xpBt6y86OX>Ng4|-+|^b1S$n-)dCa`qdQM!&E`zq@l9Q@`Fe^eb1tHr}RwHFLZ43;LyB"
        "{A>H2?`<f}PmMgKpkKNARh)`U%`a`}ou^+&?00X$+^-k?%Ga-#x!;97`i0bf`x-^R5nk5xD_6hPUJ;_-#y<Lm<bHL1jb-^M;hyv>"
        "U%#H=DfwlMeLDJu#D4d6{q*~N`t8^4(=R0UyV&0(`VI52qF=fCwekoP{WkS4(k~?UJJ3{i>qeNzG5VFO-(wzOH*S?R4bU&7_It2o"
        ">i6PF`jxNWlNU|>E*;P>r1ty#A^m=jejh%kU(hf8;@{iv>sR#qz50FintnmQ^b7i>U-||8f__22^b620{epf$zo1{xFX)$k0s5t1"
        "{5SV|_^DsMAC#?Mt55wN(k~?TyFB=)-1z;z;>RB#iysxn@Au0e4KC9!B=-B4e)#d-B9+qLSz&eT2rFxAYlXtb#@5!(&fea^!O_vl"
        ">G<*E&dx3_uC6EC+}zzyLU4C?b35Vc>f++;Eaq`?a&&ZXu(!9fv$eIcQ7FWSU+=1L_E)Nk?miyY|3$x$=H14G`re(J*CYHqj~+`8"
        "4^K}ouT$RMK0c>?eb4y$`TL&@2nY;37Zem6d_E-PLTKnu5JE#QgoK<A4h{-B7Z?~2aMs`7&+m+{?`a<&Z|_rHUY?#F9!Hn;^ozKD"
        "^Ugi>#Kt?$AEfzUnA0?t7yWu&8GiAIu(0s(h{(vOsHjWP(J?Wxu}bCTxVZTED+vi#6BCn?t|ce`3?VuBT2fMC;?;zNEAjDhahH|K"
        "*w~nu=;%vgy2!|g@bIv(qsxXXum4(9-l&-~eBk^+n!gYAT6I-%(M?s#jiXXhQc_b>)6&w?(=#$MGBY!?va(dF?Ck8EoZQ^pyu7?$"
        "U?QdvgXH9hX;dmPl9);imYy!A69bAFzaCa~v#7XAt<@j?&G~~w?+v=a=Bn~LMftam$~P+zKeNKmZkzlD6Z0DXtn+a$({v_Z4_kDn"
        "ysCLnXL!%)gXDj(-fqnesvGXzEid~<=|?4>6r25yZ+x8Q^HiU<`*zjxyZ0K@gL7MN`S%JIctO9}(rGp7$B!O<si~={v9YnC;ek+J"
        "UsqRGTU%38Q(awsU#P0AtgNW0_yd9%Ld;QR+(pbIMyjn9bJf=ymuqMcBZ?`HO#A4uTBFr%z2Wr?iv;}Epr4-@9(?|#fr0-1zP{dG"
        "ji#rkySuCFS!ZWQ$J6%qwzel~b!%%&OLKGcpAf_lEiJ9BYW0)0w)Xa?9UYyW&$_z0yL);xn%>^NzW)A!fg{rn4o}SM4R6U87J5Rz"
        "KQwIUS6=;NX=!P3QKws2n4g=Qot>GPo_;wsH90vkK0Y?4)sBvij0_JAy@1Kk(D2B}=%`jZHa0#!F*!Lk^>TW8W@dJFZf<^IL8n_>"
        "6mxzt?uvfHaQL3bGc0!ne|Y!i@bL9#2L}iH`}=!)d%L^42E)$I_V(7+=H|x6#`^l&+S=->Uca)ky!;Bn^76`xUcb63hFM=16K!s8"
        "ZEbJw>=+DUJTajdQq1|!wugsr-hJTmg@rF+$cEDf000000000000000000000000000000000000000000000000000000000000"
        "000000000000000000000000000000000000000000000000000000000000000000000000000004mhpZ*U5$-nd"
    ),
}
MASK_SHAS = {
    "Full": "ab5e72173d3ce1e12b0a7fe209873b08c77b8d9bb5a51d425d7325d0e03c156b",
    "Center": "c2be4edcd99ba67ef2d967bc0d06e1a6f3818d05c389cc0c8fb2c175ceb3f0c7"
}
build_runtime_hmi = build_alpha_hmi


def build_runtime_kzb(original, toc):
    base, prior = previous_kzb(original, toc)
    if digest(base) != BASE_KZB_SHA:
        raise ValueError("Exact 1.14.5 curve/background baseline required")
    directory = KzbDirectory(base)
    changes = {}
    for mode in ("Full", "Center"):
        raw = zlib.decompress(base64.b85decode(MASKS[mode]))
        if len(raw) != 960*360 or hashlib.sha256(raw).hexdigest() != MASK_SHAS[mode]:
            raise ValueError("Owned alpha payload changed")
        image = Image.new("RGBA", (960,360), (255,255,255,255))
        image.putalpha(Image.frombytes("L", (960,360), raw))
        stream = io.BytesIO()
        image.save(stream,"PNG",compress_level=9)
        png = stream.getvalue()
        for theme in ("Dark","Light"):
            path = "/Resource Files/Images/DX"+mode+theme+".png"
            old = Image.open(io.BytesIO(directory.read(path)[20:])).convert("RGBA")
            if old.size != image.size:
                raise ValueError("Existing mask texture dimensions changed")
            changes[path] = words(0,0,0,0,len(png))+png
    output = repack(directory,changes,{})
    saved = KzbDirectory(output)
    if saved.records.keys() != directory.records.keys():
        raise ValueError("Unexpected KZB resource topology change")
    actual = [name for name in directory.records if directory.read(name) != saved.read(name)]
    if set(actual) != set(changes):
        raise ValueError("Only four owned alpha mask resources may change")
    return output, dict(prior=prior,input_sha256=digest(original),output_sha256=digest(output),
        previous_kzb_sha256=digest(base),changed_resources=actual,
        native_mask_dimensions=[960,360],native_texture_memory_unchanged=True,
        live_oem_backgrounds_and_state_machines_unchanged=True,
        original_startup_prefab_and_foreground_unchanged=True,
        video_rectangle_and_wire_protocol_unchanged=True,
        full_and_center_alpha_adapted=True,curve_support_fixed=True,vehicle=False)
